package dev.stan.liftlog.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.stan.liftlog.MainActivity
import dev.stan.liftlog.R
import dev.stan.liftlog.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the workout alive and puts the work/rest stopwatch on the lock screen.
 *
 * The notification carries the two buttons the whole session runs on — finish a set and
 * start resting, then start the next set — so the phone never has to be unlocked between
 * sets.
 *
 * Every clock is derived from a wall-clock stamp rather than counted, so the numbers stay
 * right across doze and process death; the notification is re-posted once a second while
 * resting to put those numbers on screen.
 */
class SessionService : LifecycleService() {

    companion object {
        const val ACTION_TOGGLE = "dev.stan.liftlog.TOGGLE"
        const val ACTION_START_SET = "dev.stan.liftlog.START_SET"
        const val ACTION_EXTEND_REST = "dev.stan.liftlog.EXTEND_REST"
        /** Dismissing the alert: stop the noise, leave the rest running. */
        const val ACTION_SILENCE = "dev.stan.liftlog.SILENCE"
        /** Fired when the RPE typed into the notification's inline reply field is submitted. */
        const val ACTION_SAVE_RPE = "dev.stan.liftlog.SAVE_RPE"
        private const val KEY_RPE_INPUT = "rpe_input"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1001
    }

    private val notifications: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onCreate() {
        super.onCreate()
        createChannel()
        RestAlarm.createChannel(this)
        // The notification buttons can reach a freshly-spawned process, so rebuild the
        // session from disk before onStartCommand acts on it.
        if (!SessionManager.state.value.active) SessionManager.restore(this)

        lifecycleScope.launch {
            SessionManager.state.collectLatest { state ->
                if (!state.active) {
                    RestRinger.sync(this@SessionService, state)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    RestRinger.sync(this@SessionService, state)
                    notifications.notify(NOTIFICATION_ID, buildNotification(state))

                    // The rest lines count in seconds, and a notification does not redraw
                    // itself, so it has to be re-posted while those seconds are on screen.
                    // collectLatest cancels this loop on the next phase change, and the
                    // session ending takes the branch above — so it only ever runs while
                    // there is actually a moving number to show.
                    if (state.phase == Phase.RACKED || state.phase == Phase.REST) {
                        while (true) {
                            delay(1_000)
                            // Also where the ring's own timeout is checked — this loop runs
                            // for exactly the phases the alarm can be ringing in.
                            RestRinger.sync(this@SessionService, state)
                            // Re-posting collapses an inline reply the user has open, so the
                            // RPE prompt is left alone until it's answered — its own numbers
                            // aren't moving anyway, unlike the rest clock the rest of this
                            // loop exists to keep current.
                            if (!awaitingRpe(state)) {
                                notifications.notify(NOTIFICATION_ID, buildNotification(state))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val state = SessionManager.state.value
        if (!state.active) {
            // No workout to keep alive — going foreground here would be both pointless
            // and, on a system-initiated restart, illegal.
            stopSelf()
            return START_NOT_STICKY
        }

        // Android requires startForeground() promptly after startForegroundService(),
        // including for each of the action intents below.
        startForegroundCompat(buildNotification(state))

        when (intent?.action) {
            ACTION_TOGGLE -> SessionManager.advance(this)
            ACTION_START_SET -> SessionManager.startNextSet(this)
            ACTION_EXTEND_REST -> SessionManager.extendRest(this)
            ACTION_SILENCE -> RestRinger.silence(this)
            ACTION_SAVE_RPE -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_RPE_INPUT)?.toString().orEmpty()
                // Off-scale means a typo, same tolerance as the in-app RPE field: dropped
                // rather than saved, so the prompt just stays up for another try.
                text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..10.0 }
                    ?.let { SessionManager.setRpe(this, state.currentSetId, it) }
            }
        }
        // The session is persisted, so a system restart of this service has nothing to
        // add; MainActivity revives the notification instead.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The player is held by this service; nothing else would ever release it.
        RestRinger.release(this)
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        // A refused foreground start must not take the workout down with it.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w("SessionService", "Foreground start refused; falling back to a plain notification", it)
            notifications.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Workout session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Rest timer and set controls while a training is running"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun pending(action: String, requestCode: Int, mutable: Boolean = false): PendingIntent {
        val intent = Intent(this, SessionService::class.java).setAction(action)
        // RemoteInput needs a mutable PendingIntent to attach the typed text to (enforced
        // since Android 12); every other action stays immutable as before.
        val mutabilityFlag = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
        )
    }

    /** Racked with nothing rated yet: the notification is showing the RPE prompt in place of "Start rest". */
    private fun awaitingRpe(state: SessionState): Boolean =
        state.phase == Phase.RACKED && Repo.set(state.currentSetId)?.rpe == null

    private fun buildNotification(state: SessionState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val copy = sessionCopy(state, System.currentTimeMillis())

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setSubText("Workout session")
            // Not drawn any more, now that the body is ours — but still the only thing that
            // populates EXTRA_TITLE/EXTRA_TEXT, which is what TalkBack, notification history
            // and any surface declining to render our layout read instead. Keep them.
            .setContentTitle(copy.title)
            .setContentText(copy.fallbackText)
            .withPanel(this, copy, clockSp = 34f)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            // A finished plan has nothing to start, so the button that would start it is
            // dropped rather than left to do nothing. A just-racked set with no RPE yet
            // asks for one in place of "Start rest" — answering it is what brings "Start
            // rest" back.
            .apply {
                if (awaitingRpe(state)) {
                    val remoteInput = RemoteInput.Builder(KEY_RPE_INPUT)
                        .setLabel("RPE 1–10")
                        .build()
                    val rpeAction = NotificationCompat.Action.Builder(
                        0, "Log RPE", pending(ACTION_SAVE_RPE, 3, mutable = true)
                    )
                        .addRemoteInput(remoteInput)
                        // Platform default requires unlocking before typing into a
                        // RemoteInput from the lock screen — the whole point here is
                        // rating a set without touching the lock screen at all.
                        .setAuthenticationRequired(false)
                        .build()
                    addAction(rpeAction)
                } else if (!state.nothingToStart) {
                    addAction(0, state.actionLabel, pending(ACTION_TOGGLE, 1))
                }
            }
            .build()
    }
}
