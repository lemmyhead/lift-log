package dev.stan.liftlog.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The rest alarm's sound: an alarm that keeps ringing, rather than a notification that pings
 * once and leaves you to notice it.
 *
 * A channel sound can only ever be the ping. The system plays it when the notification posts
 * and there is no way to loop it, so ringing until the lifter actually does something has to
 * be the app's own [MediaPlayer] — held by the foreground service that is already running for
 * the session, which is why this needs no service of its own.
 *
 * It plays on [AudioAttributes.USAGE_ALARM], so it comes out of the alarm stream: audible
 * with the ringer on silent, and past Do Not Disturb, which a notification sound is not.
 *
 * ## When it rings
 *
 * Not started and stopped by the buttons — derived from the session state:
 *
 * ```
 * active && phase == REST && restTargetReached
 * ```
 *
 * true from the moment [RestAlarmReceiver] marks the target reached until the rest ends.
 * Every way out of a rest already clears that flag: "End rest" and "Start set" and "End
 * training" from either notification, the same actions taken on the app's own screen, and
 * "+1:00", which clears it and re-arms the alarm. So there is no path that ends a rest and
 * leaves the phone ringing, and no new button that has to remember to call [stop] — which
 * is the failure this design is chosen to make impossible, since the consequence of missing
 * one path is a phone that will not shut up in a public gym.
 *
 * [sync] is idempotent, so the service calls it on every state emission and on every tick of
 * its one-second loop.
 */
internal object RestRinger {

    /**
     * How long it will ring unattended before giving up.
     *
     * The brief was "until I tap", and within a session that is what happens — this is the
     * runaway guard for the case the brief does not cover: a phone left in a locker, or a
     * session never ended. Twice a long rest, so it is not reachable while training.
     */
    private const val MAX_RING_MILLIS = 5 * 60 * 1000L

    /** Buzz, gap, buzz, longer gap — repeated from index 0, so it runs until cancelled. */
    private val VIBRATION_PATTERN = longArrayOf(0, 600, 400, 600, 1_200)

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * When the current ring began, and the flag for whether one is running at all — zero
     * means not ringing. Deliberately not `player != null`: the player is absent whenever
     * the tone could not be played, and a ring that is vibrating-only is still a ring. Keyed
     * off the player, a failed tone would have [sync] calling [start] again every second,
     * re-requesting audio focus and restarting the vibration pattern each time.
     */
    private var ringingSince = 0L

    /**
     * Set when the lifter dismisses the alert, or opens the app, without ending the rest —
     * both of which mean the alarm has been heard, which is all it was for. Cleared as soon
     * as the state stops being alarm-due, so the next rest rings normally and "+1:00" rings
     * again when the extra minute is up.
     */
    private var silenced = false

    /** True while a rest is over its target and has not been dealt with. */
    private fun SessionState.alarmDue(): Boolean =
        active && phase == Phase.REST && restTargetReached

    /**
     * Bring the sound into line with [state]. Safe to call repeatedly; starts the player
     * only if it is not already running, and stops it only if it is.
     */
    fun sync(context: Context, state: SessionState) {
        if (!state.alarmDue()) {
            silenced = false
            stop(context)
            return
        }
        if (silenced) return

        if (ringingSince == 0L) {
            ringingSince = SystemClock.elapsedRealtime()
            start(context)
            return
        }
        // Checked here rather than on a timer of its own: the service ticks this once a
        // second for the whole of a rest, which is exactly when the ringing happens.
        if (SystemClock.elapsedRealtime() - ringingSince >= MAX_RING_MILLIS) {
            Log.i("RestRinger", "Nobody came; giving up after ${MAX_RING_MILLIS / 1000}s")
            silenced = true
            stop(context)
        }
    }

    /**
     * "Heard it" — silence without ending the rest. The clock keeps running and the ongoing
     * notification still offers "End rest", so nothing is lost but the noise.
     */
    fun silence(context: Context) {
        silenced = true
        stop(context)
    }

    private fun start(context: Context) {
        val uri = alarmUri(context)
        if (uri == null) {
            Log.w("RestRinger", "No alarm tone available; vibration only")
        } else {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Transient, so whatever they are listening to ducks or pauses and comes back
            // on its own once the alarm is done. Playback goes ahead whatever the answer:
            // a refused focus request is not a reason for a rest alarm to stay quiet.
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
                .also { request -> runCatching { audio?.requestAudioFocus(request) } }

            if (audio?.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                // Not fixable from here — raising it would be changing a system setting
                // behind the lifter's back. The vibration below is the fallback.
                Log.w("RestRinger", "Alarm stream is at zero volume; vibration only")
            }

            // Built into a local and only published once it is actually playing: assigning
            // through `apply` would leak the player on a throw from prepare(), because the
            // assignment never happens and nothing is left holding it to release.
            val fresh = MediaPlayer()
            runCatching {
                fresh.setAudioAttributes(attributes)
                // The screen is off and the phone is in a bag for most of a rest; without
                // this the CPU can drop out from under a loop that has to keep going.
                fresh.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                fresh.isLooping = true
                fresh.setDataSource(context, uri)
                fresh.prepare()
                fresh.start()
            }.onSuccess {
                player = fresh
            }.onFailure {
                Log.w("RestRinger", "Alarm tone would not play; vibration only", it)
                runCatching { fresh.release() }
            }
        }

        // Runs whether or not there is sound: a gym is loud, the phone is usually in a
        // pocket, and this is the half that still works at zero volume.
        runCatching {
            vibrator(context)?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
        }
    }

    /**
     * Give up the player without recording that the lifter heard anything.
     *
     * What the service calls when it is going away: the player is process-scoped state that
     * only this object can release, but a service teardown is not acknowledgement. Going
     * through [silence] here would leave a rest that is still ringing muted for good, since
     * the latch only clears once the state stops being alarm-due.
     */
    fun release(context: Context) = stop(context)

    private fun stop(context: Context) {
        player?.let { active ->
            player = null
            runCatching {
                if (active.isPlaying) active.stop()
                active.release()
            }
        }
        focusRequest?.let { request ->
            focusRequest = null
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            runCatching { audio?.abandonAudioFocusRequest(request) }
        }
        runCatching { vibrator(context)?.cancel() }
        ringingSince = 0L
    }

    /**
     * The user's own alarm tone, so it sounds like an alarm they already recognise.
     *
     * `getActualDefaultRingtoneUri` is the concrete choice and returns null when that
     * choice is "none"; `getDefaultUri` is the symbolic fallback the system resolves. The
     * ringtone and notification tones are last resorts — quieter and less alarming than
     * asked for, but better than a rest that ends in silence.
     */
    private fun alarmUri(context: Context): Uri? =
        runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) }.getOrNull()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) }.getOrNull()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
