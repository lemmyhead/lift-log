package dev.stan.liftlog.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The numbers a periodisation plan is actually built on: how much work went in per week,
 * how the recent block compares to the established baseline, and where each lift's
 * estimated max is heading.
 */
object Analytics {

    private val weekLabel = DateTimeFormatter.ofPattern("d MMM")

    data class Week(
        val monday: LocalDate,
        val label: String,
        val tonnage: Double,
        val workingTonnage: Double,
        val sets: Int,
        val reps: Int,
        val sessions: Int
    )

    data class MovementTrend(
        val name: String,
        /** One best estimated 1RM per session, oldest first. */
        val points: List<Point>
    ) {
        data class Point(val at: Long, val est1rm: Double, val bestSet: SetEntry)

        val latest: Point? get() = points.lastOrNull()
        val best: Point? get() = points.maxByOrNull { it.est1rm }

        /** Change from the session before last to the latest, as a fraction. */
        val delta: Double?
            get() {
                if (points.size < 2) return null
                val prev = points[points.size - 2].est1rm
                if (prev <= 0.0) return null
                return (points.last().est1rm - prev) / prev
            }
    }

    private fun dayOf(ms: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun weeks(
        details: List<TrainingDetail>,
        count: Int = 12,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Week> {
        val thisMonday = LocalDate.now(zone).with(DayOfWeek.MONDAY)
        val mondays = (0 until count).map { thisMonday.minusWeeks((count - 1 - it).toLong()) }

        val byWeek = details.groupBy { dayOf(it.training.startedAt, zone).with(DayOfWeek.MONDAY) }

        return mondays.map { monday ->
            val inWeek = byWeek[monday].orEmpty()
            Week(
                monday = monday,
                label = weekLabel.format(monday),
                tonnage = inWeek.sumOf { it.tonnage },
                workingTonnage = inWeek.sumOf { it.workingTonnage },
                sets = inWeek.sumOf { it.setCount },
                reps = inWeek.sumOf { it.totalReps },
                sessions = inWeek.size
            )
        }
    }

    /** Warm-ups are excluded: ramping to a heavy single is not the load that fatigues you. */
    private fun tonnageSince(details: List<TrainingDetail>, days: Long, zone: ZoneId): Double {
        val cutoff = LocalDate.now(zone).minusDays(days - 1)
        return details.filter { !dayOf(it.training.startedAt, zone).isBefore(cutoff) }
            .sumOf { it.workingTonnage }
    }

    /**
     * Acute:chronic workload ratio — this week's tonnage against the rolling 4-week
     * weekly average. Around 0.8–1.3 is steady progression; well above is a spike worth
     * a deload, well below is a taper.
     */
    fun acwr(details: List<TrainingDetail>, zone: ZoneId = ZoneId.systemDefault()): Double? {
        val acute = tonnageSince(details, 7, zone)
        val chronicWeekly = tonnageSince(details, 28, zone) / 4.0
        if (chronicWeekly <= 0.0) return null
        return acute / chronicWeekly
    }

    fun acwrVerdict(ratio: Double): String = when {
        ratio < 0.8 -> "deloading"
        ratio <= 1.3 -> "on track"
        ratio <= 1.5 -> "ramping hard"
        else -> "spike — rest soon"
    }

    fun trends(details: List<TrainingDetail>, zone: ZoneId = ZoneId.systemDefault()): List<MovementTrend> {
        val byName = mutableMapOf<String, MutableList<MovementTrend.Point>>()
        details.sortedBy { it.training.startedAt }.forEach { training ->
            training.exercises.forEach { ex ->
                val top = ex.topSet ?: return@forEach
                if (top.estimated1rm <= 0.0) return@forEach
                byName.getOrPut(ex.exercise.name) { mutableListOf() }
                    .add(MovementTrend.Point(training.training.startedAt, top.estimated1rm, top))
            }
        }
        return byName.map { (name, points) -> MovementTrend(name, points) }
            .sortedByDescending { it.points.lastOrNull()?.at ?: 0L }
    }

    /** Days since the last completed session — the simplest fatigue signal there is. */
    fun daysSinceLast(details: List<TrainingDetail>, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val last = details.filter { it.training.endedAt != null }.maxOfOrNull { it.training.startedAt } ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(dayOf(last, zone), LocalDate.now(zone))
    }
}
