package dev.stan.liftlog.data

/** One gym visit. */
data class Training(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val notes: String
) {
    val durationSeconds: Int
        get() = (((endedAt ?: System.currentTimeMillis()) - startedAt) / 1000).toInt()
}

/** A movement performed inside a training, e.g. "Back squat". */
data class Exercise(
    val id: Long,
    val trainingId: Long,
    val name: String,
    val position: Int,
    /** Rest target handed to newly planned sets; 0 means no alarm. */
    val restTargetSeconds: Int
)

/** How a set functions in the ramp — drives which sets count as real training load. */
object SetLabel {
    const val WARMUP = "warmup"
    const val WORKING = "working"
    const val BACKOFF = "backoff"
    const val DELOAD = "deload"

    val presets = listOf(WARMUP, WORKING, BACKOFF, DELOAD)
}

/**
 * A set in the plan, and — once performed — the record of it.
 *
 * Sets are written ahead of time as a ramp: each carries its own weight, reps and rest
 * target, because a working set and a warm-up have nothing in common but the bar. Actual
 * [reps] and [weight] start as copies of the plan and are corrected afterwards if the set
 * did not go as written.
 *
 * [restSeconds] is the whole interval from racking this set to starting the next one, so
 * it includes the time spent walking back to the phone. The rest *countdown* is a
 * different thing — it runs from the moment the timer is started, and is not stored.
 */
data class SetEntry(
    val id: Long,
    val exerciseId: Long,
    val position: Int,
    val plannedReps: Int,
    val plannedWeight: Double,
    val reps: Int,
    val weight: Double,
    val restTargetSeconds: Int,
    val label: String,
    val rpe: Double?,
    val startedAt: Long?,
    val completedAt: Long?,
    val workSeconds: Int,
    val restSeconds: Int?
) {
    val done: Boolean get() = completedAt != null
    val started: Boolean get() = startedAt != null

    /** Zero until the set is actually performed — a plan is not tonnage. */
    val volume: Double get() = if (done) reps * weight else 0.0

    val plannedVolume: Double get() = plannedReps * plannedWeight

    /** Warm-ups build no meaningful fatigue, so they sit outside the working load. */
    val isWarmup: Boolean get() = label.equals(SetLabel.WARMUP, ignoreCase = true)

    /** True when the set was performed differently from how it was written. */
    val deviated: Boolean get() = done && (reps != plannedReps || weight != plannedWeight)

    /** Epley estimated one-rep max — the usual input to a periodisation plan. */
    val estimated1rm: Double
        get() = if (!done || reps <= 0 || weight <= 0.0) 0.0 else weight * (1.0 + reps / 30.0)
}

data class ExerciseWithSets(val exercise: Exercise, val sets: List<SetEntry>) {
    val completed: List<SetEntry> get() = sets.filter { it.done }
    val planned: List<SetEntry> get() = sets.filter { !it.done }

    /** The set the lifter is on: the first one not yet finished. */
    val nextUp: SetEntry? get() = sets.firstOrNull { !it.done }

    val tonnage: Double get() = completed.sumOf { it.volume }
    val workingTonnage: Double get() = completed.filterNot { it.isWarmup }.sumOf { it.volume }
    val totalReps: Int get() = completed.sumOf { it.reps }
    val topSet: SetEntry? get() = completed.maxByOrNull { it.estimated1rm }
}

data class TrainingDetail(val training: Training, val exercises: List<ExerciseWithSets>) {
    val tonnage: Double get() = exercises.sumOf { it.tonnage }
    val workingTonnage: Double get() = exercises.sumOf { it.workingTonnage }
    val setCount: Int get() = exercises.sumOf { it.completed.size }
    val plannedRemaining: Int get() = exercises.sumOf { it.planned.size }
    val totalReps: Int get() = exercises.sumOf { it.totalReps }

    val avgRestSeconds: Int
        get() {
            val rests = exercises.flatMap { it.completed }.mapNotNull { it.restSeconds }
            return if (rests.isEmpty()) 0 else rests.average().toInt()
        }

    val exerciseNames: List<String> get() = exercises.map { it.exercise.name }
}
