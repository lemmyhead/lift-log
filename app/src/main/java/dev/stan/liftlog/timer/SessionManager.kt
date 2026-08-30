package dev.stan.liftlog.timer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.stan.liftlog.data.Repo
import dev.stan.liftlog.data.SetEntry
import dev.stan.liftlog.data.formatWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the set cycle. Both the UI and the lock-screen notification drive the session
 * through here, so they can never disagree about which phase is running.
 *
 * The cycle is deliberately four beats — start set, end set, start rest, end rest — because
 * racking the bar and starting the rest timer are separate moments in the gym. Rest is
 * still logged from the rack, so the gap in between is not lost.
 *
 * State is mirrored into SharedPreferences on every transition: if the process is killed
 * mid-workout, the running session and its clocks come back intact.
 */
object SessionManager {

    private const val PREFS = "liftlog_session"
    private const val K_TRAINING = "training_id"
    private const val K_STARTED = "training_started_at"
    private const val K_PHASE = "phase"
    private const val K_PHASE_AT = "phase_started_at"
    private const val K_SET_ENDED = "set_ended_at"
    private const val K_CURRENT_SET = "current_set_id"
    private const val K_DEFAULT_TARGET = "default_rest_target"

    /** Three minutes: a reasonable opening guess for compound work. */
    private const val FALLBACK_TARGET_SECONDS = 180
    private const val FALLBACK_REPS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Rest target applied to a movement being trained for the first time. */
    fun defaultRestTarget(context: Context): Int =
        prefs(context).getInt(K_DEFAULT_TARGET, FALLBACK_TARGET_SECONDS)

    fun setDefaultRestTarget(context: Context, seconds: Int) {
        prefs(context).edit().putInt(K_DEFAULT_TARGET, seconds).apply()
    }

    /** Read by the alarm receiver, which may be running in a fresh process. */
    fun persistedRestStartedAt(context: Context): Long {
        val p = prefs(context)
        val phase = p.getString(K_PHASE, Phase.READY.name)
        return if (phase == Phase.REST.name) p.getLong(K_PHASE_AT, 0L) else 0L
    }

    // ------------------------------------------------------------------ lifecycle

    fun restore(context: Context) {
        Repo.init(context)
        val p = prefs(context)
        val training = Repo.activeTraining()
        if (training == null) {
            p.edit().remove(K_TRAINING).remove(K_PHASE).remove(K_PHASE_AT)
                .remove(K_SET_ENDED).remove(K_CURRENT_SET).apply()
            _state.value = SessionState()
            return
        }

        val phase = runCatching { Phase.valueOf(p.getString(K_PHASE, Phase.READY.name)!!) }
            .getOrDefault(Phase.READY)
        val currentSetId = p.getLong(K_CURRENT_SET, -1L)
        val currentSet = currentSetId.takeIf { it > 0 }?.let { Repo.set(it) }

        _state.value = SessionState(
            trainingId = training.id,
            trainingStartedAt = training.startedAt,
            phase = phase,
            phaseStartedAt = p.getLong(K_PHASE_AT, training.startedAt),
            setEndedAt = p.getLong(K_SET_ENDED, 0L),
            currentSetId = currentSetId,
            currentExercise = Repo.lastExerciseOf(training.id)?.name.orEmpty(),
            currentSetLabel = currentSet?.let { describe(it) }.orEmpty(),
            setsThisSession = Repo.detail(training.id)?.setCount ?: 0,
            restTargetSeconds = currentSet?.restTargetSeconds ?: 0
        )
    }

    /** Brings the notification back after the app (or the whole process) was restarted. */
    fun ensureServiceRunning(context: Context) {
        if (_state.value.active) startService(context)
    }

    fun startTraining(context: Context) = scope.launch {
        Repo.init(context)
        val now = System.currentTimeMillis()
        val id = Repo.startTraining(now)
        _state.value = SessionState(
            trainingId = id,
            trainingStartedAt = now,
            phase = Phase.READY,
            phaseStartedAt = now
        )
        persist(context)
        startService(context)
    }

    fun endTraining(context: Context) = scope.launch {
        val s = _state.value
        if (!s.active) return@launch
        val now = System.currentTimeMillis()
        if (s.phase == Phase.SET) closeSet(s, now)
        if (s.phase == Phase.RACKED || s.phase == Phase.REST) recordRest(s, now)
        RestAlarm.cancel(context)
        Repo.endTraining(s.trainingId, now)
        _state.value = SessionState()
        persist(context)
    }

    // ---------------------------------------------------------------- the set cycle

    /** Single entry point for the lock-screen button: advance one beat. */
    fun advance(context: Context) = scope.launch {
        val s = _state.value
        if (!s.active) return@launch
        when (s.phase) {
            Phase.READY -> beginSet(context, s)
            Phase.SET -> endSet(context, s)
            Phase.RACKED -> beginRest(context, s)
            Phase.REST -> endRest(context, s)
        }
    }

    private fun beginSet(context: Context, s: SessionState) {
        val now = System.currentTimeMillis()

        // Closing out the previous set's rest here — not at "end rest" — is what makes the
        // logged rest the full interval between two sets rather than just the timed part.
        if (s.setEndedAt > 0L && s.currentSetId > 0) {
            Repo.updateRest(s.currentSetId, ((now - s.setEndedAt) / 1000).toInt().coerceAtLeast(0))
        }

        val exercise = Repo.lastExerciseOf(s.trainingId)
            ?: Repo.exercise(Repo.addExercise(s.trainingId, "Exercise 1", defaultRestTarget(context)))
            ?: return

        val target = Repo.nextPlannedSet(exercise.id) ?: run {
            // Nothing planned left — carry the last set forward so the session never stalls.
            val previous = Repo.lastSetOf(exercise.id)
            val id = Repo.planSet(
                exerciseId = exercise.id,
                reps = previous?.reps ?: FALLBACK_REPS,
                weight = previous?.weight ?: 0.0,
                restTargetSeconds = previous?.restTargetSeconds ?: exercise.restTargetSeconds,
                label = previous?.label.orEmpty(),
                now = now
            )
            Repo.set(id) ?: return
        }

        Repo.markSetStarted(target.id, now)
        _state.value = s.copy(
            phase = Phase.SET,
            phaseStartedAt = now,
            setEndedAt = 0L,
            currentSetId = target.id,
            currentExercise = exercise.name,
            currentSetLabel = describe(target),
            restTargetSeconds = target.restTargetSeconds,
            restTargetReached = false
        )
        persist(context)
        buzz(context, 40)
        startService(context)
    }

    private fun endSet(context: Context, s: SessionState) {
        val now = System.currentTimeMillis()
        closeSet(s, now)
        _state.value = s.copy(
            phase = Phase.RACKED,
            phaseStartedAt = now,
            setEndedAt = now,
            setsThisSession = Repo.detail(s.trainingId)?.setCount ?: (s.setsThisSession + 1)
        )
        persist(context)
        buzz(context, 40)
        startService(context)
    }

    private fun beginRest(context: Context, s: SessionState) {
        val now = System.currentTimeMillis()
        _state.value = s.copy(
            phase = Phase.REST,
            phaseStartedAt = now,
            restTargetReached = false
        )
        persist(context)
        RestAlarm.schedule(context, now, s.restTargetSeconds, s.currentExercise)
        buzz(context, 40)
        startService(context)
    }

    private fun endRest(context: Context, s: SessionState) {
        val now = System.currentTimeMillis()
        RestAlarm.cancel(context)
        // Interim figure, so the set is never left without a rest if the session ends here.
        recordRest(s, now)
        _state.value = s.copy(
            phase = Phase.READY,
            phaseStartedAt = now,
            restTargetReached = false
        )
        persist(context)
        buzz(context, 40)
        startService(context)
    }

    private fun closeSet(s: SessionState, now: Long) {
        if (s.currentSetId <= 0) return
        val started = Repo.set(s.currentSetId)?.startedAt ?: s.phaseStartedAt
        Repo.markSetCompleted(s.currentSetId, now, ((now - started) / 1000).toInt().coerceAtLeast(0))
    }

    private fun recordRest(s: SessionState, now: Long) {
        if (s.currentSetId <= 0 || s.setEndedAt <= 0L) return
        Repo.updateRest(s.currentSetId, ((now - s.setEndedAt) / 1000).toInt().coerceAtLeast(0))
    }

    // ------------------------------------------------------------------- plan edits

    fun addExercise(context: Context, name: String, copyLastPlan: Boolean) = scope.launch {
        val s = _state.value
        if (!s.active) return@launch
        val now = System.currentTimeMillis()
        val id = Repo.addExercise(s.trainingId, name, defaultRestTarget(context))
        if (copyLastPlan) Repo.copyPlanFromLastSession(id, name, now)
        _state.value = _state.value.copy(
            currentExercise = name,
            currentSetLabel = Repo.nextPlannedSet(id)?.let { describe(it) }.orEmpty()
        )
        startService(context)
    }

    fun planSet(
        context: Context,
        exerciseId: Long,
        reps: Int,
        weight: Double,
        restTargetSeconds: Int,
        label: String
    ) = scope.launch {
        Repo.planSet(exerciseId, reps, weight, restTargetSeconds, label, System.currentTimeMillis())
        setDefaultRestTarget(context, restTargetSeconds)
        refreshCurrentSet(context)
    }

    fun updatePlannedSet(
        context: Context,
        setId: Long,
        reps: Int,
        weight: Double,
        restTargetSeconds: Int,
        label: String
    ) = scope.launch {
        Repo.updatePlannedSet(setId, reps, weight, restTargetSeconds, label)

        // Retarget a countdown already running against this set.
        val s = _state.value
        if (s.currentSetId == setId) {
            _state.value = s.copy(restTargetSeconds = restTargetSeconds, restTargetReached = false)
            if (s.phase == Phase.REST) {
                RestAlarm.schedule(context, s.phaseStartedAt, restTargetSeconds, s.currentExercise)
            }
        }
        refreshCurrentSet(context)
    }

    fun deleteSet(context: Context, setId: Long) = scope.launch {
        Repo.deleteSet(setId)
        refreshCurrentSet(context)
    }

    fun updateSetActuals(context: Context, setId: Long, reps: Int, weight: Double, rpe: Double?) =
        scope.launch {
            Repo.updateSet(setId, reps, weight, rpe)
            refreshCurrentSet(context)
        }

    fun refreshCounts() = scope.launch {
        val s = _state.value
        if (!s.active) return@launch
        _state.value = s.copy(setsThisSession = Repo.detail(s.trainingId)?.setCount ?: s.setsThisSession)
    }

    private fun refreshCurrentSet(context: Context) {
        val s = _state.value
        if (!s.active) return
        val exercise = Repo.lastExerciseOf(s.trainingId)
        val shown = when (s.phase) {
            Phase.READY -> exercise?.let { Repo.nextPlannedSet(it.id) }
            else -> s.currentSetId.takeIf { it > 0 }?.let { Repo.set(it) }
        }
        _state.value = s.copy(
            currentExercise = exercise?.name.orEmpty(),
            currentSetLabel = shown?.let { describe(it) }.orEmpty(),
            restTargetSeconds = when (s.phase) {
                Phase.READY -> shown?.restTargetSeconds ?: s.restTargetSeconds
                else -> s.restTargetSeconds
            },
            setsThisSession = Repo.detail(s.trainingId)?.setCount ?: s.setsThisSession
        )
        startService(context)
    }

    /** Called by the alarm receiver so the ongoing notification can say the rest is up. */
    fun markRestTargetReached(context: Context) = scope.launch {
        val s = _state.value
        if (!s.active || s.phase != Phase.REST) return@launch
        _state.value = s.copy(restTargetReached = true)
        startService(context)
    }

    // ------------------------------------------------------------------- internals

    private fun describe(set: SetEntry): String {
        val core = "${formatWeight(set.weight)} kg × ${set.reps}"
        return if (set.label.isBlank()) core else "$core · ${set.label}"
    }

    private fun persist(context: Context) {
        val s = _state.value
        prefs(context).edit()
            .putLong(K_TRAINING, s.trainingId)
            .putLong(K_STARTED, s.trainingStartedAt)
            .putString(K_PHASE, s.phase.name)
            .putLong(K_PHASE_AT, s.phaseStartedAt)
            .putLong(K_SET_ENDED, s.setEndedAt)
            .putLong(K_CURRENT_SET, s.currentSetId)
            .apply()
    }

    private fun startService(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, SessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    private fun buzz(context: Context, ms: Long) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching {
            vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
