package dev.stan.liftlog.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A calendar week, identified by the Monday that opens it.
 *
 * The Monday itself is the key rather than an (ISO year, ISO week) pair, because ordering,
 * distance and "the next week" are then exact and free. A year/week pair needs arithmetic that
 * knows some ISO years have 53 weeks — and ISO week 1 of a year can begin in December, so
 * 2026-12-28 belongs to 2027-W01. Get that wrong once and every week after it is off by one,
 * silently, for good. The year and week numbers are derived here for display only, where being
 * wrong would be cosmetic.
 */
data class TrainingWeek(val monday: LocalDate) : Comparable<TrainingWeek> {

    /** ISO-8601 week-based year — not necessarily [monday]'s calendar year. */
    val isoYear: Int get() = monday.get(IsoFields.WEEK_BASED_YEAR)

    val isoWeek: Int get() = monday.get(WeekFields.ISO.weekOfWeekBasedYear())

    /** "2026-W36" — the durable name of this week, and sortable as text. */
    val label: String get() = "%04d-W%02d".format(Locale.US, isoYear, isoWeek)

    fun plusWeeks(n: Long) = TrainingWeek(monday.plusWeeks(n))

    override fun compareTo(other: TrainingWeek): Int = monday.compareTo(other.monday)
}

/**
 * One week of the training log, with its rolling four-week context.
 *
 * Every number is full precision: rounding happens where a value is shown or written, never
 * here. Rounding in the model would push the error from [weeklyVolumeKg] into
 * [chronicVolumeKg] and on into [acwr], which is exactly the mistake the reference mock data
 * makes — its grip readiness is computed from an already-rounded chronic, so it disagrees with
 * an exact calculation by up to 0.1 in some weeks. That is the mock being sloppy, not a bug
 * here.
 *
 * A null means *no measurement*, never zero. The distinction is load-bearing: a week with no
 * grip reading is not a week of zero grip strength.
 */
data class WeekMetrics(
    /** 1-based position in the dense series; 1 is the week of the earliest session. */
    val weekNumber: Int,
    val week: TrainingWeek,
    val sessionCount: Int,
    val setCount: Int,
    /** Sum of every completed set's volume. Zero in a week with no training. */
    val weeklyVolumeKg: Double,
    /** Mean weekly volume over the trailing [Fatigue.WINDOW_WEEKS], this week included. */
    val chronicVolumeKg: Double,
    /** Acute-to-chronic workload ratio; null when there is no load at all to compare. */
    val acwr: Double?,
    val avgRpe: Double?,
    val chronicRpe: Double?,
    val rpeSampleCount: Int,
    val avgGripKg: Double?,
    val chronicGripKg: Double?,
    /** Acute grip as a percentage of the chronic baseline; null without both. */
    val gripReadinessPct: Double?,
    val gripSampleCount: Int
) {
    val hasTraining: Boolean get() = sessionCount > 0

    /** How far this week's effort sits above its own baseline; null without both. */
    val rpeDelta: Double? get() = if (avgRpe != null && chronicRpe != null) avgRpe - chronicRpe else null
}

enum class ReadinessLevel { INSUFFICIENT, OK, WATCH, DELOAD }

/** What the three metrics say together, and why. */
data class Readiness(
    val level: ReadinessLevel,
    val headline: String,
    val reasons: List<String>
)

/**
 * Weekly fatigue metrics derived from the training log.
 *
 * Pure: same log and same clock in, same numbers out. Nothing here reads the database or the
 * session, so it can be exercised from a scratch `main()` — which is the only test channel
 * this project has.
 *
 * ## Two blind spots, stated rather than fixed
 *
 * **Bodyweight work is invisible.** Volume is `reps × weight`, so thirty sets of chin-ups at a
 * recorded weight of zero contribute nothing. A week of gymnastics therefore reads as a rest
 * week: the chronic baseline falls, and the following week's [WeekMetrics.acwr] spikes for no
 * real reason. This is the largest silent error in the model, and fixing it needs a volume
 * model that understands unloaded work.
 *
 * **Weekly average RPE is set-weighted, not session-weighted.** A twenty-set session outweighs
 * a five-set one four to one inside the same week. That is the right statistic for "average
 * effort per unit of work", but it is not "the average of how my sessions felt".
 */
object Fatigue {

    /** Weeks in the chronic window, the current one included. */
    const val WINDOW_WEEKS = 4

    /**
     * Ten years. A single corrupt timestamp near the epoch would otherwise build a spine of
     * ~2,900 weeks, which is a multi-thousand-row export and an ANR from one bad row. When it
     * fires the series is truncated from the *front*, which drops the ghost and keeps the real
     * history — at the cost of re-basing every week number.
     */
    private const val MAX_WEEKS = 520

    /** The RPE scale. Anything outside it was a typo, and one 75 among forty sets moves the
     *  weekly mean by nearly two points — enough on its own to call for a deload. */
    private val RPE_RANGE = 1.0..10.0

    /**
     * Slack on the threshold comparisons below.
     *
     * RPE and grip readiness are decimal quantities that land exactly on the thresholds in
     * ordinary use — an RPE half a point over the baseline, a grip reading at 95%. In binary
     * they do not: an 8.2 against a chronic 7.7 is a delta of 0.49999999999999911, which slips
     * under a bare `>= 0.5` and drops the signal entirely. Observed, not hypothetical: it is
     * week 22 of the reference data.
     */
    private const val EPS = 1e-9

    fun weekOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): TrainingWeek =
        TrainingWeek(
            Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        )

    /**
     * The whole log as a dense weekly series, ascending — one entry per calendar week from the
     * earliest session through the week containing [now], weeks with no training included.
     *
     * ## Why every session is bucketed by its start, not each set by its completion
     *
     * Grip is one reading per session. Bucketing sets individually would put a Sunday-night
     * session's grip in one week and most of its volume in the next, so the three metrics would
     * stop describing the same training. It also keeps a week number a property of the session,
     * which is what lets the per-set export carry its week's figures honestly. The cost is that
     * a Sunday session running past midnight counts entirely as Sunday's week — which is also
     * how a lifter describes it.
     *
     * ## Why gaps are kept, and counted differently per metric
     *
     * Volume treats a week without training as a real zero, because not training *is*
     * detraining: that is the whole point of the ratio. Skip the gaps instead and the week you
     * come back from a layoff reads about 1.0 — "right on track" — at the moment the risk is
     * highest. RPE and grip take no sample from an empty week. RPE has no meaningful zero, and
     * grip measures the lifter rather than the training; a zero in either would read as
     * effortless training or as no strength at all.
     *
     * The series runs to [now] rather than to the last session for the same reason: stopping at
     * the last session would show someone three weeks into a layoff the ratio they had before
     * it, still saying they are fine.
     *
     * Timestamps carry no zone of their own, so weeks are resolved against [zone] at read time.
     * A session logged in Tokyo and read in London can cross a Monday and land in the
     * neighbouring week.
     */
    fun weekly(
        details: List<TrainingDetail>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<WeekMetrics> {
        if (details.isEmpty()) return emptyList()

        val raw = HashMap<TrainingWeek, WeekRaw>()
        details.forEach { d ->
            val bucket = raw.getOrPut(weekOf(d.training.startedAt, zone)) { WeekRaw() }
            val sets = d.exercises.flatMap { it.completed }
            bucket.sessions++
            bucket.sets += sets.size
            bucket.volume += sets.sumOf { it.volume }
            sets.mapNotNull { it.rpe }.filter { it in RPE_RANGE }.forEach {
                bucket.rpeSum += it
                bucket.rpeCount++
            }
            d.training.gripKg?.takeIf { it.isFinite() && it > 0.0 }?.let {
                bucket.gripSum += it
                bucket.gripCount++
            }
        }

        // maxOf guards a clock that has gone backwards, or a session stamped in the future.
        var start = raw.keys.min()
        val end = maxOf(raw.keys.max(), weekOf(now, zone))
        var span = ChronoUnit.WEEKS.between(start.monday, end.monday).toInt() + 1
        if (span > MAX_WEEKS) {
            start = end.plusWeeks(-(MAX_WEEKS - 1).toLong())
            span = MAX_WEEKS
        }

        // Laid out as parallel arrays first so each week's window is a plain slice. Carrying
        // running sums instead would save a constant factor at this size and is where the
        // off-by-one lives.
        val weeks = ArrayList<TrainingWeek>(span)
        val volumes = DoubleArray(span)
        val rpes = arrayOfNulls<Double>(span)
        val grips = arrayOfNulls<Double>(span)
        for (i in 0 until span) {
            val week = start.plusWeeks(i.toLong())
            val r = raw[week]
            weeks += week
            volumes[i] = r?.volume ?: 0.0
            rpes[i] = r?.avgRpe()
            grips[i] = r?.avgGrip()
        }

        return (0 until span).map { i ->
            val lo = maxOf(0, i - (WINDOW_WEEKS - 1))
            val r = raw[weeks[i]]

            // Partial windows average over the weeks that exist — they are not padded out to
            // four. That is what makes the first week's ratio 1.0 and its readiness 100%: the
            // week is being compared with itself, which is a tautology rather than a verdict,
            // and why `verdict` refuses to judge that early.
            val chronicVolume = volumes.copyOfRange(lo, i + 1).average()
            val chronicRpe = rpes.copyOfRange(lo, i + 1).filterNotNull()
                .takeIf { it.isNotEmpty() }?.average()
            val chronicGrip = grips.copyOfRange(lo, i + 1).filterNotNull()
                .takeIf { it.isNotEmpty() }?.average()
            val acuteGrip = grips[i]

            WeekMetrics(
                weekNumber = i + 1,
                week = weeks[i],
                sessionCount = r?.sessions ?: 0,
                setCount = r?.sets ?: 0,
                weeklyVolumeKg = volumes[i],
                chronicVolumeKg = chronicVolume,
                // A mean of non-negative numbers is zero only when every term is, so this is a
                // genuine 0/0: no load anywhere in the window to compare against. Null, not
                // 1.0 — which would tell someone who has not lifted in a month that they are
                // perfectly on track.
                acwr = if (chronicVolume > 0.0) volumes[i] / chronicVolume else null,
                avgRpe = rpes[i],
                chronicRpe = chronicRpe,
                rpeSampleCount = r?.rpeCount ?: 0,
                avgGripKg = acuteGrip,
                chronicGripKg = chronicGrip,
                gripReadinessPct =
                    if (acuteGrip != null && chronicGrip != null && chronicGrip > 0.0) {
                        acuteGrip / chronicGrip * 100.0
                    } else {
                        null
                    },
                gripSampleCount = r?.gripCount ?: 0
            )
        }
    }

    /** Lookup by week, for joining a session to its week's figures. */
    fun byWeek(weeks: List<WeekMetrics>): Map<TrainingWeek, WeekMetrics> =
        weeks.associateBy { it.week }

    /** The week containing [now] — the last of the series, unless the clock has moved back. */
    fun current(
        weeks: List<WeekMetrics>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): WeekMetrics? {
        if (weeks.isEmpty()) return null
        val here = weekOf(now, zone)
        return weeks.firstOrNull { it.week == here } ?: weeks.last()
    }

    /**
     * What the three metrics say together about [at] — the current week unless given.
     *
     * Scored rather than ranked, because the signals are not interchangeable. Two of them can
     * call for a deload alone: a load spike, which is the most actionable thing here, and a
     * grip crash, which is the only objective measurement of the three. A high RPE on its own
     * cannot — it is the noisiest input and the easiest to mistype, so it needs corroboration
     * before it tells anyone to stop training.
     *
     * Under-training is [ReadinessLevel.WATCH] and never [ReadinessLevel.DELOAD]. Prescribing
     * a deload to someone already doing too little is the obvious failure of a symmetric
     * threshold, and it would make the banner worse than nothing.
     */
    fun verdict(
        weeks: List<WeekMetrics>,
        at: WeekMetrics? = null,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Readiness {
        val m = at ?: current(weeks, now, zone)
            ?: return Readiness(ReadinessLevel.INSUFFICIENT, "Nothing logged yet", emptyList())

        // A week still being trained holds one session against three complete ones, so its
        // ratio starts near zero on Monday and climbs all week. Only the *low* branch is
        // suppressed: a spike that is already high mid-week is real and will only grow. The
        // export still carries the raw figure — this suppression is the verdict's alone.
        val inProgress = m.week == weekOf(now, zone)

        val acwrSignal = when {
            m.acwr == null -> Signal.UNKNOWN
            m.acwr > 1.50 -> Signal.DELOAD
            m.acwr > 1.30 -> Signal.WATCH
            m.acwr < 0.80 -> if (inProgress) Signal.OK else Signal.WATCH
            else -> Signal.OK
        }
        val delta = m.rpeDelta
        val rpeSignal = when {
            delta == null -> Signal.UNKNOWN
            delta >= 1.0 - EPS -> Signal.DELOAD
            delta >= 0.5 - EPS -> Signal.WATCH
            else -> Signal.OK
        }
        val gripSignal = when {
            m.gripReadinessPct == null -> Signal.UNKNOWN
            // One reading taken after heavy pulling rather than fresh moves the week's mean by
            // its whole error, so a single measurement is never enough to call for a deload.
            m.gripReadinessPct <= 90.0 + EPS ->
                if (m.gripSampleCount >= 2) Signal.DELOAD else Signal.WATCH
            m.gripReadinessPct <= 95.0 + EPS -> Signal.WATCH
            else -> Signal.OK
        }

        val signals = listOf(acwrSignal, rpeSignal, gripSignal)
        val known = signals.count { it != Signal.UNKNOWN }

        // In week one the window is the week, so the ratio is 1.00 and readiness is 100% by
        // construction. Dressing a tautology up as a health assessment would be dishonest.
        if (m.weekNumber < 3 || known < 2) {
            val missing = (3 - m.weekNumber).coerceAtLeast(0)
            return Readiness(
                ReadinessLevel.INSUFFICIENT,
                "Not enough history yet",
                listOfNotNull(
                    if (missing > 0) {
                        "Another ${plural(missing, "week")} of logging before these numbers " +
                            "mean anything — the four-week baseline is still comparing this " +
                            "week with itself."
                    } else {
                        null
                    },
                    if (known < 2) {
                        "Only $known of the three signals has data. Log an RPE on your working " +
                            "sets and a grip reading before you lift."
                    } else {
                        null
                    }
                )
            )
        }

        val score = signals.sumOf { it.points }
        val level = when {
            acwrSignal == Signal.DELOAD || gripSignal == Signal.DELOAD -> ReadinessLevel.DELOAD
            score >= 3 -> ReadinessLevel.DELOAD
            score >= 1 -> ReadinessLevel.WATCH
            else -> ReadinessLevel.OK
        }

        val reasons = buildList {
            if (acwrSignal != Signal.OK && m.acwr != null) {
                add(
                    when {
                        m.acwr > 1.50 -> "Load ratio ${"%.2f".format(m.acwr)} — this week is far " +
                            "above your four-week average, and past 1.50 is where the risk climbs."
                        m.acwr > 1.30 -> "Load ratio ${"%.2f".format(m.acwr)}, over the 1.30 ceiling."
                        else -> "Load ratio ${"%.2f".format(m.acwr)} — you are training below " +
                            "your own baseline, not above it."
                    }
                )
            }
            if (rpeSignal != Signal.OK && delta != null && m.chronicRpe != null) {
                add(
                    "Effort is up ${"%.1f".format(delta)} on your chronic RPE of " +
                        "${"%.1f".format(m.chronicRpe)} — the same work is feeling harder."
                )
            }
            if (gripSignal != Signal.OK && m.gripReadinessPct != null) {
                add(
                    "Grip is at ${"%.0f".format(m.gripReadinessPct)}% of your four-week baseline" +
                        if (m.gripSampleCount < 2) " — on one reading, so measure again to confirm." else "."
                )
            }
            if (isEmpty()) {
                add(
                    "Load, effort and grip are all where they should be" +
                        if (known < 3) ", on the ${plural(known, "signal")} being measured." else "."
                )
            }
        }

        val headline = when (level) {
            ReadinessLevel.DELOAD -> when {
                acwrSignal == Signal.DELOAD -> "Your load has spiked — back it off"
                gripSignal == Signal.DELOAD -> "Your nervous system is lagging — deload"
                else -> "Every signal is pointing the same way — deload"
            }
            ReadinessLevel.WATCH -> "Worth watching — hold volume flat this week"
            ReadinessLevel.OK -> "Absorbing the work"
            ReadinessLevel.INSUFFICIENT -> "Not enough history yet"
        }
        return Readiness(level, headline, reasons)
    }

    private enum class Signal(val points: Int) {
        UNKNOWN(0), OK(0), WATCH(1), DELOAD(2)
    }

    private class WeekRaw {
        var sessions = 0
        var sets = 0
        var volume = 0.0
        var rpeSum = 0.0
        var rpeCount = 0
        var gripSum = 0.0
        var gripCount = 0

        fun avgRpe(): Double? = if (rpeCount == 0) null else rpeSum / rpeCount
        fun avgGrip(): Double? = if (gripCount == 0) null else gripSum / gripCount
    }
}
