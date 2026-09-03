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
        val named = notifiedSet(training.id, phase, currentSetId)
        // With no set in hand the card previews what is next, which is what
        // refreshCurrentSet shows too — so reopening the app and editing the plan agree.
        val shown = if (phase == Phase.READY) named else currentSet
        val setCount = Repo.detail(training.id)?.setCount ?: 0

        _state.value = SessionState(
            trainingId = training.id,
            trainingStartedAt = training.startedAt,
            phase = phase,
            phaseStartedAt = p.getLong(K_PHASE_AT, training.startedAt),
            setEndedAt = p.getLong(K_SET_ENDED, 0L),
            currentSetId = currentSetId,
            currentExercise = exerciseNameFor(training.id, currentSet ?: named),
            currentSetLabel = shown?.let { describe(it) }.orEmpty(),
            currentSetPosition = named?.let { setNumber(it.exerciseId, it.id) } ?: 0,
            currentSetCore = named?.let { describeCore(it) }.orEmpty(),
            setsThisSession = setCount,
            restTargetSeconds = shown?.restTargetSeconds ?: 0,
            planComplete = named == null && setCount > 0
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

    /**
     * The rest alarm's primary button: close the rest and go straight under the bar.
     *
     * Two beats in one press, because when the alarm sounds the lifter is standing at the
     * bar — being handed "end rest" and then having to find "start set" is a press too many.
     */
    fun startNextSet(context: Context) = scope.launch {
        val s = _state.value
        if (!s.active) return@launch
        when (s.phase) {
            Phase.REST -> {
                endRest(context, s)
                // endRest has replaced the state; beginSet must see the new one.
                beginSet(context, _state.value)
            }
            Phase.READY -> beginSet(context, s)
            // Racked but never started resting: closing the gap is the sensible read.
            Phase.RACKED -> beginSet(context, s)
            Phase.SET -> Unit
        }
    }

    /**
     * The alarm's "+1:00": a one-off snooze.
     *
     * Deliberately does not touch the set's stored rest target or the default for future
     * sets — needing another minute today is not a change to the plan.
     */
    fun extendRest(context: Context, extraSeconds: Int = 60) = scope.launch {
        val s = _state.value
        if (!s.active || s.phase != Phase.REST) return@launch
        // Measured from whichever is later, the target or where the rest actually stands:
        // an alert left ringing for two minutes must still buy a full extra minute, and
        // target + 60 would already be in the past and re-fire on the spot.
        val elapsed = s.phaseSeconds(System.currentTimeMillis())
        val target = maxOf(s.restTargetSeconds, elapsed) + extraSeconds
        val extended = s.copy(restTargetSeconds = target, restTargetReached = false)
        _state.value = extended
        persist(context)
        // Rescheduled against the unchanged rest start, so the clock on screen keeps
        // running unbroken. schedule() cancels first, clearing the alert that prompted this.
        RestAlarm.schedule(context, s.phaseStartedAt, target, extended)
        startService(context)
    }

    private fun beginSet(context: Context, s: SessionState) {
        val now = System.currentTimeMillis()

        // Closing out the previous set's rest here — not at "end rest" — is what makes the
        // logged rest the full interval between two sets rather than just the timed part.
        if (s.setEndedAt > 0L && s.currentSetId > 0) {
            Repo.updateRest(s.currentSetId, ((now - s.setEndedAt) / 1000).toInt().coerceAtLeast(0))
        }

        // The set is chosen first and the movement read off it, not the other way round:
        // what comes next is a property of the plan, not of whichever exercise happened to
        // be added last. A null here means the plan is finished, and beginSet doing nothing
        // is what lets every caller stay guard-free.
        val target = Repo.nextPlannedSetInTraining(s.trainingId)
            ?: openingSet(context, s.trainingId, now)
            ?: return

        Repo.markSetStarted(target.id, now)
        _state.value = s.copy(
            phase = Phase.SET,
            phaseStartedAt = now,
            setEndedAt = 0L,
            currentSetId = target.id,
            currentExercise = exerciseNameFor(s.trainingId, target),
            currentSetLabel = describe(target),
            currentSetPosition = setNumber(target.exerciseId, target.id),
            currentSetCore = describeCore(target),
            restTargetSeconds = target.restTargetSeconds,
            restTargetReached = false,
            planComplete = false
        )
        persist(context)
        buzz(context, 40)
        startService(context)
    }

    /**
     * A session that was started but never planned still has to go somewhere when the
     * button is pressed, so the first exercise and set are invented as they always were.
     *
     * Any set at all — planned or performed — means the plan was written and, since nothing
     * is pending, finished. Then this returns null and both surfaces offer "End training"
     * rather than quietly appending a set nobody asked for.
     */
    private fun openingSet(context: Context, trainingId: Long, now: Long): SetEntry? {
        val plan = Repo.detail(trainingId) ?: return null
        if (plan.exercises.any { it.sets.isNotEmpty() }) return null
        val exercise = plan.exercises.firstOrNull()?.exercise
            ?: Repo.exercise(Repo.addExercise(trainingId, "Exercise 1", defaultRestTarget(context)))
            ?: return null
        val id = Repo.planSet(exercise.id, FALLBACK_REPS, 0.0, exercise.restTargetSeconds, "", now)
        return Repo.set(id)
    }

    private fun endSet(context: Context, s: SessionState) {
        val now = System.currentTimeMillis()
        closeSet(s, now)
        // The set is logged, so the two notification surfaces stop naming it and start
        // naming what to come back to.
        val named = notifiedSet(s.trainingId, Phase.RACKED, s.currentSetId)
        val setCount = Repo.detail(s.trainingId)?.setCount ?: (s.setsThisSession + 1)
        _state.value = s.copy(
            phase = Phase.RACKED,
            phaseStartedAt = now,
            setEndedAt = now,
            // The name moves with the number and the load: at an exercise boundary the
            // alarm ships all three as one sentence, so they cannot come from two sets.
            currentExercise = exerciseNameFor(s.trainingId, named),
            currentSetPosition = named?.let { setNumber(it.exerciseId, it.id) } ?: 0,
            currentSetCore = named?.let { describeCore(it) }.orEmpty(),
            setsThisSession = setCount,
            planComplete = named == null && setCount > 0
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
        RestAlarm.schedule(context, now, s.restTargetSeconds, _state.value)
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
        // Back to READY means nothing is in hand, so what is displayed becomes a preview of
        // what is next — or of nothing, once the plan is done. This is the same resolution
        // restore does, so the three ways into READY agree.
        refreshCurrentSet(context)
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
        // Adding a movement appends to the plan; it does not repoint the session at it.
        // What is displayed follows plan order, so it only moves here if this really is next.
        refreshCurrentSet(context)
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
                RestAlarm.schedule(context, s.phaseStartedAt, restTargetSeconds, _state.value)
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
        val named = notifiedSet(s.trainingId, s.phase, s.currentSetId)
        // In READY the two agree — what is shown is what is next. From RACKED on they part
        // company on purpose: the card keeps naming the set just finished while the
        // notification names the one to load.
        val shown =
            if (s.phase == Phase.READY) named
            else s.currentSetId.takeIf { it > 0 }?.let { Repo.set(it) }
        val setCount = Repo.detail(s.trainingId)?.setCount ?: s.setsThisSession
        _state.value = s.copy(
            currentExercise = exerciseNameFor(s.trainingId, named),
            currentSetLabel = shown?.let { describe(it) }.orEmpty(),
            currentSetPosition = named?.let { setNumber(it.exerciseId, it.id) } ?: 0,
            currentSetCore = named?.let { describeCore(it) }.orEmpty(),
            restTargetSeconds = when (s.phase) {
                Phase.READY -> shown?.restTargetSeconds ?: s.restTargetSeconds
                else -> s.restTargetSeconds
            },
            setsThisSession = setCount,
            planComplete = named == null && setCount > 0
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
        val core = describeCore(set)
        return if (set.label.isBlank()) core else "$core · ${set.label}"
    }

    /** The load alone, for the notifications — they carry the set number instead of the ramp label. */
    private fun describeCore(set: SetEntry): String = "${formatWeight(set.weight)} kg × ${set.reps}"

    /**
     * Which set of the ramp this is, counting from 1.
     *
     * Derived by index rather than from [SetEntry.position], because deleting a set leaves a
     * gap in the stored positions — after removing the second of five, `position + 1` would
     * start counting 1, 3, 4, 5.
     */
    private fun setNumber(exerciseId: Long, setId: Long): Int =
        Repo.setsOf(exerciseId).indexOfFirst { it.id == setId } + 1

    /**
     * The set the notifications should name.
     *
     * While a set is in hand that is the set itself. Once it is racked the lock screen's job
     * is to say what to load next — which is exactly what the alarm's "Next:" line promises —
     * so from [Phase.RACKED] onwards this resolves to the next planned set rather than the
     * one just finished. Safe to call after [closeSet], which is what takes the finished set
     * out of [Repo.nextPlannedSetInTraining]'s reach — a set still in progress is unfinished
     * too, so that ordering is what keeps the two apart. Null once the plan is done.
     */
    private fun notifiedSet(trainingId: Long, phase: Phase, currentSetId: Long): SetEntry? =
        if (phase == Phase.SET) currentSetId.takeIf { it > 0 }?.let { Repo.set(it) }
        else Repo.nextPlannedSetInTraining(trainingId)

    /**
     * The movement to name, derived from whichever set is being named — so a header can
     * never announce one exercise above another exercise's load. It matters most for the
     * alarm, which ships [SessionState.currentExercise], [SessionState.currentSetPosition]
     * and [SessionState.currentSetCore] as a single sentence.
     *
     * With no set to name — plan finished, or nothing planned yet — it falls back to the
     * exercise of the last set actually performed, which is the movement the lifter is
     * demonstrably still on, and finally to the top of the plan.
     */
    private fun exerciseNameFor(trainingId: Long, set: SetEntry?): String =
        (set?.let { Repo.exercise(it.exerciseId) }
            ?: Repo.latestSetInTraining(trainingId)?.let { Repo.exercise(it.exerciseId) }
            ?: Repo.exercisesOf(trainingId).firstOrNull())
            ?.name.orEmpty()

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
