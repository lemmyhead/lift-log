package dev.stan.liftlog.data

/** One gym visit. */
data class Training(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val notes: String,
    /**
     * Grip strength measured before lifting, in kg; null when it was never taken.
     *
     * One reading for the whole session, and deliberately a *pre*-session one: taken fresh it
     * measures how the nervous system is firing today, taken afterwards it measures how much
     * the session took out of you. Averaging the two kinds together over weeks would make the
     * chronic baseline mean nothing.
     */
    val gripKg: Double? = null
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

/** How a set functions in the ramp — a label carried on each set and shown in the export. */
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

    /** Zero until the set is actually performed — a planned set has no volume yet. */
    val volume: Double get() = if (done) reps * weight else 0.0
}

/**
 * 1-based number of a set within its exercise's ramp; 0 when it is not in the list.
 *
 * Counted by index rather than read from [SetEntry.position], because deleting a set leaves
 * a gap in the stored positions — after removing the second of five, `position + 1` would
 * number the remaining four 1, 3, 4, 5.
 */
fun List<SetEntry>.numberOf(set: SetEntry): Int = indexOfFirst { it.id == set.id } + 1

/** The set's number within its own exercise, wherever only the session is in hand. */
fun TrainingDetail?.numberOfSet(set: SetEntry): Int =
    this?.exercises?.firstOrNull { it.exercise.id == set.exerciseId }?.sets?.numberOf(set) ?: 0

data class ExerciseWithSets(val exercise: Exercise, val sets: List<SetEntry>) {
    val completed: List<SetEntry> get() = sets.filter { it.done }
    val planned: List<SetEntry> get() = sets.filter { !it.done }

    /** The next set within this exercise: the first one not yet finished. */
    val nextUp: SetEntry? get() = sets.firstOrNull { !it.done }

    val totalReps: Int get() = completed.sumOf { it.reps }
}

data class TrainingDetail(val training: Training, val exercises: List<ExerciseWithSets>) {
    val setCount: Int get() = exercises.sumOf { it.completed.size }
    val plannedRemaining: Int get() = exercises.sumOf { it.planned.size }

    /**
     * The set the lifter is on, training-wide: the first unfinished set in plan order, and
     * the only one anywhere that is next.
     *
     * The screen's mirror of `Repo.nextPlannedSetInTraining` — [Repo.detail] returns
     * exercises by position and each ramp by position, so walking them in order is the
     * same traversal that query does.
     */
    val nextUp: SetEntry? get() = exercises.firstNotNullOfOrNull { it.nextUp }
    val totalReps: Int get() = exercises.sumOf { it.totalReps }

    val avgRestSeconds: Int
        get() {
            val rests = exercises.flatMap { it.completed }.mapNotNull { it.restSeconds }
            return if (rests.isEmpty()) 0 else rests.average().toInt()
        }

    val exerciseNames: List<String> get() = exercises.map { it.exercise.name }
}
