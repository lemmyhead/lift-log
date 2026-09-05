package dev.stan.liftlog.timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.stan.liftlog.MainActivity
import dev.stan.liftlog.R

/**
 * The "your rest is up" alert.
 *
 * A delayed callback inside the service would usually work, but a phone sitting still in a
 * gym bag is exactly the situation where the system starts deferring them. An exact
 * wake-up alarm is the one mechanism that survives doze, so that is what schedules the
 * end of each rest period.
 */
object RestAlarm {

    const val ACTION_FIRE = "dev.stan.liftlog.REST_DONE"
    const val EXTRA_REST_STARTED_AT = "rest_started_at"
    const val EXTRA_TARGET_SECONDS = "target_seconds"
    const val EXTRA_EXERCISE = "exercise"
    const val EXTRA_SET_POSITION = "set_position"
    const val EXTRA_SET_CORE = "set_core"
    const val EXTRA_PLAN_COMPLETE = "plan_complete"

    const val CHANNEL_ID = "rest_done"
    private const val NOTIFICATION_ID = 1002
    private const val REQUEST_CODE = 7001

    private fun alarms(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(
        context: Context,
        restStartedAt: Long,
        targetSeconds: Int,
        exercise: String,
        setPosition: Int = 0,
        setCore: String = "",
        planComplete: Boolean = false
    ): PendingIntent {
        val intent = Intent(context.applicationContext, RestAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_REST_STARTED_AT, restStartedAt)
            .putExtra(EXTRA_TARGET_SECONDS, targetSeconds)
            .putExtra(EXTRA_EXERCISE, exercise)
            .putExtra(EXTRA_SET_POSITION, setPosition)
            .putExtra(EXTRA_SET_CORE, setCore)
            .putExtra(EXTRA_PLAN_COMPLETE, planComplete)
        return PendingIntent.getBroadcast(
            context.applicationContext, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** True when the system will honour an exact alarm; false means the alert may run late. */
    fun canFireExactly(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms(context).canScheduleExactAlarms()

    /**
     * The alert has to name the set that is coming up, and it may be built in a process
     * that has just been spawned to receive the broadcast — so the details ride along in
     * the intent rather than being read back from the database.
     */
    fun schedule(context: Context, restStartedAt: Long, targetSeconds: Int, state: SessionState) {
        cancel(context)
        if (targetSeconds <= 0) return

        val app = context.applicationContext
        val triggerAt = restStartedAt + targetSeconds * 1000L
        val pending = pendingIntent(
            app, restStartedAt, targetSeconds,
            state.currentExercise, state.currentSetPosition, state.currentSetCore,
            state.planComplete
        )

        runCatching {
            if (canFireExactly(app)) {
                alarms(app).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // Without the exact-alarm privilege this can drift by a minute or so, which
                // still beats no alert at all.
                alarms(app).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { alarms(app).cancel(pendingIntent(app, 0L, 0, "")) }
        notifications(app).cancel(NOTIFICATION_ID)
    }

    private fun notifications(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Audible and heads-up, unlike the silent ongoing session notification. Created once —
     * channel settings are fixed after the first launch, so this is deliberately explicit.
     *
     * The tone here is the one-off ping the system plays on posting; the alarm that keeps
     * going is [RestRinger], which the service holds. Both is deliberate. A channel sound
     * cannot be changed after the channel exists, so quieting this ping would mean a new
     * channel id — and HyperOS files channels it reads as silent away from the lock screen,
     * which is the behaviour this app's whole setup section exists to defeat. The ping also
     * rides USAGE_NOTIFICATION_EVENT, so it is suppressed on silent and under Do Not
     * Disturb, exactly where the alarm stream still sounds.
     */
    fun createChannel(context: Context) {
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rest finished",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Sounds when your rest period is up"
            setSound(sound, attributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 250, 400)
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        notifications(context).createNotificationChannel(channel)
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context.applicationContext, SessionService::class.java).setAction(action)
        return PendingIntent.getService(
            context.applicationContext, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun alert(
        context: Context,
        targetSeconds: Int,
        exercise: String,
        setPosition: Int,
        setCore: String,
        planComplete: Boolean
    ) {
        createChannel(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val copy = restDoneCopy(targetSeconds, exercise, setPosition, setCore, planComplete)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setSubText("Rest complete")
            // Not drawn under a custom view, but still what fills EXTRA_TITLE/EXTRA_TEXT —
            // and this is the notification that has to survive being read aloud.
            .setContentTitle(copy.title)
            .setContentText(copy.fallbackText)
            // headsUp because this is an IMPORTANCE_HIGH channel: the alert is seen as a
            // heads-up far more often than it is seen in the shade.
            .withPanel(context, copy, headsUp = true)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            // Swiping it away means "heard it": the noise stops, the rest keeps running,
            // and the ongoing notification still carries "End rest".
            .setDeleteIntent(action(context, SessionService.ACTION_SILENCE, 6))
            // Nothing left to go back under the bar for, so neither button it would
            // normally carry means anything: the only honest offer is to close the session.
            .apply {
                if (planComplete) {
                    addAction(0, "End training", action(context, SessionService.ACTION_END, 5))
                } else {
                    addAction(0, "Start set", action(context, SessionService.ACTION_START_SET, 3))
                    addAction(0, "+1:00", action(context, SessionService.ACTION_EXTEND_REST, 4))
                }
            }
            .build()

        notifications(context).notify(NOTIFICATION_ID, notification)
    }
}

class RestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RestAlarm.ACTION_FIRE) return

        val scheduledFor = intent.getLongExtra(RestAlarm.EXTRA_REST_STARTED_AT, 0L)
        val target = intent.getIntExtra(RestAlarm.EXTRA_TARGET_SECONDS, 0)
        val exercise = intent.getStringExtra(RestAlarm.EXTRA_EXERCISE).orEmpty()
        val setPosition = intent.getIntExtra(RestAlarm.EXTRA_SET_POSITION, 0)
        val setCore = intent.getStringExtra(RestAlarm.EXTRA_SET_CORE).orEmpty()
        val planComplete = intent.getBooleanExtra(RestAlarm.EXTRA_PLAN_COMPLETE, false)

        // A rest that was already ended must not still ring. Checked against the persisted
        // stamp rather than in-memory state, because this can run in a fresh process.
        val current = SessionManager.persistedRestStartedAt(context)
        if (scheduledFor != 0L && current != scheduledFor) return

        RestAlarm.alert(context, target, exercise, setPosition, setCore, planComplete)
        SessionManager.markRestTargetReached(context)
    }
}
