package dev.stan.liftlog.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

private const val DB_NAME = "liftlog.db"
private const val DB_VERSION = 3

class LiftDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE training(
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              started_at  INTEGER NOT NULL,
              ended_at    INTEGER,
              notes       TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE exercise(
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              training_id INTEGER NOT NULL REFERENCES training(id) ON DELETE CASCADE,
              name        TEXT NOT NULL,
              position    INTEGER NOT NULL,
              rest_target_seconds INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE set_entry(
              id                  INTEGER PRIMARY KEY AUTOINCREMENT,
              exercise_id         INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,
              position            INTEGER NOT NULL,
              planned_reps        INTEGER NOT NULL DEFAULT 0,
              planned_weight      REAL NOT NULL DEFAULT 0,
              reps                INTEGER NOT NULL,
              weight              REAL NOT NULL,
              rest_target_seconds INTEGER NOT NULL DEFAULT 0,
              label               TEXT NOT NULL DEFAULT '',
              rpe                 REAL,
              logged_at           INTEGER NOT NULL,
              started_at          INTEGER,
              completed_at        INTEGER,
              work_seconds        INTEGER NOT NULL DEFAULT 0,
              rest_seconds        INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_exercise_training ON exercise(training_id)")
        db.execSQL("CREATE INDEX idx_set_exercise ON set_entry(exercise_id)")
        db.execSQL("CREATE INDEX idx_training_started ON training(started_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations only ever add — an existing training log is never dropped.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE exercise ADD COLUMN rest_target_seconds INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE set_entry ADD COLUMN planned_reps INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE set_entry ADD COLUMN planned_weight REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE set_entry ADD COLUMN rest_target_seconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE set_entry ADD COLUMN label TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE set_entry ADD COLUMN started_at INTEGER")
            db.execSQL("ALTER TABLE set_entry ADD COLUMN completed_at INTEGER")
            // Everything already in the log was performed, so plan and actual are the same
            // and the set counts as finished at the moment it was written.
            db.execSQL("UPDATE set_entry SET planned_reps = reps, planned_weight = weight")
            db.execSQL("UPDATE set_entry SET completed_at = logged_at, started_at = logged_at")
            db.execSQL(
                """
                UPDATE set_entry SET rest_target_seconds =
                  COALESCE((SELECT e.rest_target_seconds FROM exercise e WHERE e.id = set_entry.exercise_id), 0)
                """.trimIndent()
            )
        }
    }
}

private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

private fun Cursor.toTraining() = Training(
    id = getLong(0),
    startedAt = getLong(1),
    endedAt = longOrNull(2),
    notes = getString(3) ?: ""
)

private fun Cursor.toExercise() = Exercise(
    id = getLong(0),
    trainingId = getLong(1),
    name = getString(2),
    position = getInt(3),
    restTargetSeconds = getInt(4)
)

private fun Cursor.toSet() = SetEntry(
    id = getLong(0),
    exerciseId = getLong(1),
    position = getInt(2),
    plannedReps = getInt(3),
    plannedWeight = getDouble(4),
    reps = getInt(5),
    weight = getDouble(6),
    restTargetSeconds = getInt(7),
    label = getString(8) ?: "",
    rpe = doubleOrNull(9),
    startedAt = longOrNull(10),
    completedAt = longOrNull(11),
    workSeconds = getInt(12),
    restSeconds = intOrNull(13)
)

private const val TRAINING_COLS = "id, started_at, ended_at, notes"
private const val EXERCISE_COLS = "id, training_id, name, position, rest_target_seconds"
private const val SET_COLS =
    "id, exercise_id, position, planned_reps, planned_weight, reps, weight, " +
        "rest_target_seconds, label, rpe, started_at, completed_at, work_seconds, rest_seconds"

/** Same columns, table-qualified: plain `id` is ambiguous once set_entry is joined to exercise. */
private const val JOINED_SET_COLS =
    "s.id, s.exercise_id, s.position, s.planned_reps, s.planned_weight, s.reps, s.weight, " +
        "s.rest_target_seconds, s.label, s.rpe, s.started_at, s.completed_at, s.work_seconds, s.rest_seconds"

/**
 * Single access point to the log. Calls are synchronous; screens read through a
 * background coroutine and re-read whenever [revision] changes.
 */
object Repo {

    private lateinit var helper: LiftDb

    /** Bumped after every write so observers know to re-query. */
    val revision = MutableStateFlow(0)

    fun init(context: Context) {
        if (!::helper.isInitialized) helper = LiftDb(context)
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun <T> write(block: () -> T): T = block().also { revision.value++ }

    // ---------------------------------------------------------------- trainings

    fun activeTraining(): Training? =
        db.rawQuery(
            "SELECT $TRAINING_COLS FROM training WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1",
            null
        ).use { if (it.moveToFirst()) it.toTraining() else null }

    fun training(id: Long): Training? =
        db.rawQuery("SELECT $TRAINING_COLS FROM training WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toTraining() else null }

    fun startTraining(now: Long): Long = write {
        db.insert("training", null, ContentValues().apply {
            put("started_at", now)
            putNull("ended_at")
            put("notes", "")
        })
    }

    fun endTraining(id: Long, now: Long) = write {
        db.update("training", ContentValues().apply { put("ended_at", now) }, "id = ?", arrayOf(id.toString()))
    }

    fun setNotes(id: Long, notes: String) = write {
        db.update("training", ContentValues().apply { put("notes", notes) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteTraining(id: Long) = write {
        db.delete("training", "id = ?", arrayOf(id.toString()))
    }

    fun allTrainingIds(): List<Long> =
        db.rawQuery("SELECT id FROM training ORDER BY started_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    // ---------------------------------------------------------------- exercises

    fun exercisesOf(trainingId: Long): List<Exercise> =
        db.rawQuery(
            "SELECT $EXERCISE_COLS FROM exercise WHERE training_id = ? ORDER BY position",
            arrayOf(trainingId.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toExercise()) } }

    fun lastExerciseOf(trainingId: Long): Exercise? =
        db.rawQuery(
            "SELECT $EXERCISE_COLS FROM exercise WHERE training_id = ? ORDER BY position DESC LIMIT 1",
            arrayOf(trainingId.toString())
        ).use { if (it.moveToFirst()) it.toExercise() else null }

    /**
     * [fallbackTarget] is only consulted for a movement never trained before — otherwise the
     * rest target you last used for it carries over.
     */
    fun addExercise(trainingId: Long, name: String, fallbackTarget: Int): Long = write {
        val next = (lastExerciseOf(trainingId)?.position ?: -1) + 1
        db.insert("exercise", null, ContentValues().apply {
            put("training_id", trainingId)
            put("name", name.trim())
            put("position", next)
            put("rest_target_seconds", lastRestTargetForName(name) ?: fallbackTarget)
        })
    }

    /** The rest target this movement carried the last time it was trained. */
    fun lastRestTargetForName(name: String): Int? =
        db.rawQuery(
            "SELECT rest_target_seconds FROM exercise WHERE name = ? COLLATE NOCASE ORDER BY id DESC LIMIT 1",
            arrayOf(name.trim())
        ).use { if (it.moveToFirst()) it.getInt(0) else null }

    fun setRestTarget(exerciseId: Long, seconds: Int) = write {
        db.update(
            "exercise",
            ContentValues().apply { put("rest_target_seconds", seconds) },
            "id = ?", arrayOf(exerciseId.toString())
        )
    }

    fun exercise(id: Long): Exercise? =
        db.rawQuery("SELECT $EXERCISE_COLS FROM exercise WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toExercise() else null }

    fun renameExercise(id: Long, name: String) = write {
        db.update("exercise", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteExercise(id: Long) = write {
        db.delete("exercise", "id = ?", arrayOf(id.toString()))
    }

    /** Names already used, most-used first — powers the add-exercise suggestions. */
    fun exerciseNameSuggestions(limit: Int = 30): List<String> =
        db.rawQuery(
            "SELECT name, COUNT(*) c, MAX(id) m FROM exercise GROUP BY name ORDER BY c DESC, m DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // --------------------------------------------------------------------- sets

    fun setsOf(exerciseId: Long): List<SetEntry> =
        db.rawQuery(
            "SELECT $SET_COLS FROM set_entry WHERE exercise_id = ? ORDER BY position",
            arrayOf(exerciseId.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSet()) } }

    fun lastSetOf(exerciseId: Long): SetEntry? =
        db.rawQuery(
            "SELECT $SET_COLS FROM set_entry WHERE exercise_id = ? ORDER BY position DESC LIMIT 1",
            arrayOf(exerciseId.toString())
        ).use { if (it.moveToFirst()) it.toSet() else null }

    /** Most recent set ever recorded for a movement — used to seed reps/weight defaults. */
    fun lastSetForName(name: String): SetEntry? =
        db.rawQuery(
            """
            SELECT $JOINED_SET_COLS FROM set_entry s
            JOIN exercise e ON e.id = s.exercise_id
            WHERE e.name = ? COLLATE NOCASE
            ORDER BY s.logged_at DESC LIMIT 1
            """.trimIndent(),
            arrayOf(name)
        ).use { if (it.moveToFirst()) it.toSet() else null }

    /** Appends a set to an exercise's plan. Nothing is performed until it is started. */
    fun planSet(
        exerciseId: Long,
        reps: Int,
        weight: Double,
        restTargetSeconds: Int,
        label: String,
        now: Long
    ): Long = write {
        val next = (lastSetOf(exerciseId)?.position ?: -1) + 1
        db.insert("set_entry", null, ContentValues().apply {
            put("exercise_id", exerciseId)
            put("position", next)
            put("planned_reps", reps)
            put("planned_weight", weight)
            put("reps", reps)
            put("weight", weight)
            put("rest_target_seconds", restTargetSeconds)
            put("label", label)
            putNull("rpe")
            put("logged_at", now)
            putNull("started_at")
            putNull("completed_at")
            put("work_seconds", 0)
            putNull("rest_seconds")
        })
    }

    /** Edits a set that has not been performed yet — plan and actual move together. */
    fun updatePlannedSet(
        id: Long,
        reps: Int,
        weight: Double,
        restTargetSeconds: Int,
        label: String
    ) = write {
        db.update("set_entry", ContentValues().apply {
            put("planned_reps", reps)
            put("planned_weight", weight)
            put("reps", reps)
            put("weight", weight)
            put("rest_target_seconds", restTargetSeconds)
            put("label", label)
        }, "id = ?", arrayOf(id.toString()))
    }

    /** Corrects what actually happened, leaving the plan intact for comparison. */
    fun updateSet(id: Long, reps: Int, weight: Double, rpe: Double?) = write {
        db.update("set_entry", ContentValues().apply {
            put("reps", reps)
            put("weight", weight)
            if (rpe == null) putNull("rpe") else put("rpe", rpe)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun markSetStarted(id: Long, now: Long) = write {
        db.update(
            "set_entry",
            ContentValues().apply { put("started_at", now) },
            "id = ?", arrayOf(id.toString())
        )
    }

    fun markSetCompleted(id: Long, now: Long, workSeconds: Int) = write {
        db.update("set_entry", ContentValues().apply {
            put("completed_at", now)
            put("work_seconds", workSeconds)
            put("logged_at", now)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun updateRest(id: Long, restSeconds: Int) = write {
        db.update(
            "set_entry",
            ContentValues().apply { put("rest_seconds", restSeconds) },
            "id = ?", arrayOf(id.toString())
        )
    }

    fun deleteSet(id: Long) = write {
        db.delete("set_entry", "id = ?", arrayOf(id.toString()))
    }

    fun set(id: Long): SetEntry? =
        db.rawQuery("SELECT $SET_COLS FROM set_entry WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toSet() else null }

    /**
     * The next set to perform anywhere in this training: the first unfinished set in plan
     * order — exercises by their position, then sets by theirs.
     *
     * Training-wide rather than per-exercise because the plan is written up front, every
     * movement and its whole ramp, and then run top to bottom. Asking a single exercise for
     * its next set cannot see the sets still pending in the exercise above it, so a session
     * would start at whichever movement was added last.
     *
     * [SetEntry.position] is per-exercise, so it only orders within a movement — the
     * exercise's own position is what puts the ramps in sequence. Planning a set on an
     * earlier exercise therefore makes it next again, which is the plan being followed
     * rather than an oversight.
     */
    fun nextPlannedSetInTraining(trainingId: Long): SetEntry? =
        db.rawQuery(
            """
            SELECT $JOINED_SET_COLS FROM set_entry s
            JOIN exercise e ON e.id = s.exercise_id
            WHERE e.training_id = ? AND s.completed_at IS NULL
            ORDER BY e.position, s.position, s.id LIMIT 1
            """.trimIndent(),
            arrayOf(trainingId.toString())
        ).use { if (it.moveToFirst()) it.toSet() else null }

    /**
     * Copies the ramp this movement was last trained with into a new exercise. Periodisation
     * usually moves only the working weight week to week, so starting from last time beats
     * starting from nothing.
     */
    fun copyPlanFromLastSession(exerciseId: Long, name: String, now: Long): Int = write {
        val source = db.rawQuery(
            """
            SELECT e.id FROM exercise e
            WHERE e.name = ? COLLATE NOCASE AND e.id <> ?
              AND EXISTS (SELECT 1 FROM set_entry s WHERE s.exercise_id = e.id)
            ORDER BY e.id DESC LIMIT 1
            """.trimIndent(),
            arrayOf(name.trim(), exerciseId.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@write 0

        val template = setsOf(source)
        template.forEachIndexed { index, previous ->
            db.insert("set_entry", null, ContentValues().apply {
                put("exercise_id", exerciseId)
                put("position", index)
                put("planned_reps", previous.reps)
                put("planned_weight", previous.weight)
                put("reps", previous.reps)
                put("weight", previous.weight)
                put("rest_target_seconds", previous.restTargetSeconds)
                put("label", previous.label)
                putNull("rpe")
                put("logged_at", now)
                putNull("started_at")
                putNull("completed_at")
                put("work_seconds", 0)
                putNull("rest_seconds")
            })
        }
        template.size
    }

    /** The set the rest timer belongs to: newest set anywhere in this training. */
    fun latestSetInTraining(trainingId: Long): SetEntry? =
        db.rawQuery(
            """
            SELECT $JOINED_SET_COLS FROM set_entry s
            JOIN exercise e ON e.id = s.exercise_id
            WHERE e.training_id = ? AND s.completed_at IS NOT NULL
            ORDER BY s.completed_at DESC, s.id DESC LIMIT 1
            """.trimIndent(),
            arrayOf(trainingId.toString())
        ).use { if (it.moveToFirst()) it.toSet() else null }

    // ------------------------------------------------------------------ reading

    fun detail(trainingId: Long): TrainingDetail? {
        val t = training(trainingId) ?: return null
        val ex = exercisesOf(trainingId).map { ExerciseWithSets(it, setsOf(it.id)) }
        return TrainingDetail(t, ex)
    }

    fun allDetails(): List<TrainingDetail> = allTrainingIds().mapNotNull { detail(it) }
}
