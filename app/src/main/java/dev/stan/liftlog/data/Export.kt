package dev.stan.liftlog.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One row per set, flat enough to drop straight into a spreadsheet.
 */
object Export {

    private val iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private fun stamp(ms: Long?): String =
        if (ms == null) "" else iso.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime())

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun buildCsv(details: List<TrainingDetail>): String = buildString {
        appendLine(
            listOf(
                "session_id", "session_start", "session_end", "session_duration_s",
                "exercise_order", "exercise", "set_number", "label",
                "planned_reps", "planned_weight_kg", "reps", "weight_kg",
                "rpe", "volume_kg", "rest_target_s", "work_s", "rest_s",
                "completed_at"
            ).joinToString(",")
        )
        details.sortedBy { it.training.startedAt }.forEach { d ->
            d.exercises.forEach { ex ->
                ex.completed.forEach { s ->
                    appendLine(
                        listOf(
                            d.training.id.toString(),
                            stamp(d.training.startedAt),
                            stamp(d.training.endedAt),
                            d.training.durationSeconds.toString(),
                            (ex.exercise.position + 1).toString(),
                            escape(ex.exercise.name),
                            (s.position + 1).toString(),
                            escape(s.label),
                            s.plannedReps.toString(),
                            formatWeight(s.plannedWeight),
                            s.reps.toString(),
                            formatWeight(s.weight),
                            s.rpe?.let { formatWeight(it) } ?: "",
                            formatWeight(s.volume),
                            s.restTargetSeconds.toString(),
                            s.workSeconds.toString(),
                            s.restSeconds?.toString() ?: "",
                            stamp(s.completedAt)
                        ).joinToString(",")
                    )
                }
            }
        }
    }

    /** Writes the CSV to app cache and returns a share intent for it. */
    fun shareIntent(context: Context, details: List<TrainingDetail>): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "liftlog-export.csv")
        file.writeText(buildCsv(details))

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Lift Log export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Export training log"
        )
    }
}
