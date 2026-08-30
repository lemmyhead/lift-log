package dev.stan.liftlog.timer

/**
 * Where the lifter is in the set cycle.
 *
 * Racking the bar and starting the rest timer are separate events, because in the gym they
 * are: you re-rack, put the collars away, then pick up the phone. [RACKED] is that gap. It
 * still counts as rest in the log — only the countdown waits for [REST].
 */
enum class Phase {
    /** Between sets, with nothing running. Next action is starting the set. */
    READY,

    /** A set is under way. */
    SET,

    /** Set finished, rest accumulating, countdown not started yet. */
    RACKED,

    /** Rest countdown running against this set's target. */
    REST;

    val actionLabel: String
        get() = when (this) {
            READY -> "Start set"
            SET -> "End set"
            RACKED -> "Start rest"
            REST -> "End rest"
        }

    val title: String
        get() = when (this) {
            READY -> "Ready for next set"
            SET -> "Set in progress"
            RACKED -> "Racked"
            REST -> "Resting"
        }
}

/**
 * Live state of the workout in progress.
 *
 * Every clock is a wall-clock stamp rather than a tick counter, so the numbers stay correct
 * across doze, a restart of the UI, or the notification being the only thing awake.
 */
data class SessionState(
    val trainingId: Long = -1L,
    val trainingStartedAt: Long = 0L,
    val phase: Phase = Phase.READY,
    /** When the current phase began — what the on-screen and notification clocks count from. */
    val phaseStartedAt: Long = 0L,
    /** When the current set was racked; rest is measured from here, gaps included. */
    val setEndedAt: Long = 0L,
    /** The set being worked or just finished. */
    val currentSetId: Long = -1L,
    val currentExercise: String = "",
    /** Human description of the set in hand, e.g. "160 kg × 5". */
    val currentSetLabel: String = "",
    val setsThisSession: Int = 0,
    /** Rest target of the set in hand; 0 disables the alarm. */
    val restTargetSeconds: Int = 0,
    val restTargetReached: Boolean = false
) {
    val active: Boolean get() = trainingId > 0
    val resting: Boolean get() = phase == Phase.REST
    val working: Boolean get() = phase == Phase.SET

    fun phaseSeconds(now: Long): Int = secondsSince(phaseStartedAt, now)
    fun trainingSeconds(now: Long): Int = secondsSince(trainingStartedAt, now)

    /** Rest so far, counted from racking rather than from starting the countdown. */
    fun totalRestSeconds(now: Long): Int =
        if (phase == Phase.RACKED || phase == Phase.REST) secondsSince(setEndedAt, now) else 0

    private fun secondsSince(stamp: Long, now: Long): Int =
        if (stamp <= 0L) 0 else ((now - stamp) / 1000).toInt().coerceAtLeast(0)

    /** Seconds left on the countdown, or null when past it / no target / not counting down. */
    fun restRemaining(now: Long): Int? {
        if (phase != Phase.REST || restTargetSeconds <= 0) return null
        val left = restTargetSeconds - phaseSeconds(now)
        return if (left > 0) left else null
    }

    /** How far the countdown has overrun its target, or null if still inside it. */
    fun restOverrun(now: Long): Int? {
        if (phase != Phase.REST || restTargetSeconds <= 0) return null
        val over = phaseSeconds(now) - restTargetSeconds
        return if (over > 0) over else null
    }
}
