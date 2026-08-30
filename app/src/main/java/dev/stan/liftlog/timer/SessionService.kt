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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.stan.liftlog.MainActivity
import dev.stan.liftlog.R
import dev.stan.liftlog.data.formatClock
import dev.stan.liftlog.data.plural
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the workout alive and puts the work/rest stopwatch on the lock screen.
 *
 * The notification carries the two buttons the whole session runs on — finish a set and
 * start resting, then start the next set — so the phone never has to be unlocked between
 * sets. Elapsed time is rendered by the platform chronometer from a wall-clock stamp, so
 * it keeps ticking correctly even while this process is idle.
 */
class SessionService : LifecycleService() {

    companion object {
        const val ACTION_TOGGLE = "dev.stan.liftlog.TOGGLE"
        const val ACTION_END = "dev.stan.liftlog.END"
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifications.notify(NOTIFICATION_ID, buildNotification(state))
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
            ACTION_END -> SessionManager.endTraining(this)
        }
        // The session is persisted, so a system restart of this service has nothing to
        // add; MainActivity revives the notification instead.
        return START_NOT_STICKY
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

    private fun pending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, SessionService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(state: SessionState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = System.currentTimeMillis()
        val overrun = state.restOverrun(now)
        val remaining = state.restRemaining(now)

        val title = if (state.phase == Phase.REST && state.restTargetReached) {
            "Rest is up"
        } else {
            state.phase.title
        }

        val movement = state.currentExercise.ifBlank { "No exercise yet" }
        val set = state.currentSetLabel.ifBlank { plural(state.setsThisSession, "set") + " logged" }

        val subtitle = when (state.phase) {
            Phase.REST -> when {
                overrun != null -> "$movement · ${formatClock(overrun)} over target"
                remaining != null -> "$movement · ${formatClock(remaining)} left"
                else -> "$movement · $set"
            }
            // Rest is already accruing here even though the countdown has not been started.
            Phase.RACKED -> "$movement · resting ${formatClock(state.totalRestSeconds(now))}"
            else -> "$movement · $set"
        }

        val toggleLabel = state.phase.actionLabel

        val base = state.phaseStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setWhen(base)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, toggleLabel, pending(ACTION_TOGGLE, 1))
            .addAction(0, "End training", pending(ACTION_END, 2))
            .build()
    }
}
