package dev.stan.liftlog.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The log as CSV: one file of one row per set, one of one row per week.
 *
 * The two are written together and always from the same [WeekMetrics] series, so a set row's
 * `week_number` names the same week as the row carrying that number in the weekly file.
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

    /**
     * A number as CSV data rather than as display text — always a dot, whatever the locale.
     *
     * [formatWeight] is the display equivalent and goes through `"%.1f".format(w)`, which
     * formats against the default locale. On a phone set to German, French or Russian that
     * yields "62,5", and since these values are written to the row without going through
     * [escape], a single fractional lift used to emit a bare comma and shift every later
     * column of the file one to the right. On screen the comma is correct, which is why
     * [formatWeight] is left alone and the serialising path is separate.
     *
     * Null and non-finite both become empty. Blank is how this file has always said "no
     * value", and it is what keeps a spreadsheet's AVERAGE from counting a missing reading as
     * a zero; "NaN" would simply break the parse.
     */
    private fun num(v: Double?): String = when {
        v == null || !v.isFinite() -> ""
        v == Math.rint(v) && Math.abs(v) < 1e15 -> v.toLong().toString()
        else -> String.format(Locale.US, "%.1f", v)
    }

    /** As [num], at a fixed number of decimals. */
    private fun num(v: Double?, decimals: Int): String =
        if (v == null || !v.isFinite()) "" else String.format(Locale.US, "%.${decimals}f", v)

    private val SET_HEADER = listOf(
        "session_id", "session_start", "session_end", "session_duration_s",
        "exercise_order", "exercise", "set_number", "label",
        "planned_reps", "planned_weight_kg", "reps", "weight_kg",
        "rpe", "volume_kg", "rest_target_s", "work_s", "rest_s",
        "completed_at",
        // The week each session falls in, so the flat file pivots without a join.
        "week_number", "session_grip_kg",
        "weekly_volume_kg", "acwr", "chronic_rpe_4wk", "grip_readiness_pct"
    )

    private val WEEK_HEADER = listOf(
        "week_number", "weekly_volume_kg", "chronic_volume_4wk_kg", "acwr",
        "avg_rpe", "chronic_rpe_4wk",
        "avg_grip_strength_kg", "chronic_grip_4wk_kg", "grip_readiness_pct"
    )

    /**
     * One row per performed set, with its week's fatigue figures repeated on each row.
     *
     * [weeks] must be `Fatigue.weekly(details)` for these same [details]. Hand it a series
     * built over a different set of sessions and `weekly_volume_kg` stops equalling the sum of
     * that week's `volume_kg` cells — which is the one reconciliation a reader can do to check
     * the file against itself.
     */
    fun buildCsv(details: List<TrainingDetail>, weeks: List<WeekMetrics>): String = buildString {
        appendLine(SET_HEADER.joinToString(","))
        val byWeek = Fatigue.byWeek(weeks)
        details.sortedBy { it.training.startedAt }.forEach { d ->
            // Resolved once per session, not once per set: every set of a session shares its
            // week by construction, because the session is what gets bucketed.
            val m = byWeek[Fatigue.weekOf(d.training.startedAt)]
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
                            num(s.plannedWeight),
                            s.reps.toString(),
                            num(s.weight),
                            num(s.rpe),
                            num(s.volume),
                            s.restTargetSeconds.toString(),
                            s.workSeconds.toString(),
                            s.restSeconds?.toString() ?: "",
                            stamp(s.completedAt),
                            m?.weekNumber?.toString() ?: "",
                            // This session's own reading, not the week's mean — the column
                            // name says session, and two sessions in a week can differ.
                            num(d.training.gripKg, 1),
                            num(m?.weeklyVolumeKg, 1),
                            num(m?.acwr, 2),
                            num(m?.chronicRpe, 1),
                            num(m?.gripReadinessPct, 1)
                        ).joinToString(",")
                    )
                }
            }
        }
    }

    /** One row per calendar week, weeks without training included. */
    fun buildFatigueCsv(weeks: List<WeekMetrics>): String = buildString {
        appendLine(WEEK_HEADER.joinToString(","))
        weeks.forEach { m ->
            appendLine(
                listOf(
                    m.weekNumber.toString(),
                    num(m.weeklyVolumeKg, 1),
                    num(m.chronicVolumeKg, 1),
                    num(m.acwr, 2),
                    num(m.avgRpe, 1),
                    num(m.chronicRpe, 1),
                    num(m.avgGripKg, 1),
                    num(m.chronicGripKg, 1),
                    num(m.gripReadinessPct, 1)
                ).joinToString(",")
            )
        }
    }

    /**
     * Writes both files to the app cache and returns their uris, in share order.
     *
     * Blocking: builds the whole log into two strings and writes them. Call it off the main
     * thread — the work grows with the length of the training log, so what is imperceptible
     * on a fresh install is not on a two-year one.
     *
     * [now] is taken from the caller rather than read here so both files describe the same
     * instant. Read separately, an export running across midnight on a Sunday would end the
     * two series a week apart.
     */
    fun writeExports(
        context: Context,
        details: List<TrainingDetail>,
        now: Long = System.currentTimeMillis()
    ): ArrayList<Uri> {
        val weeks = Fatigue.weekly(details, now)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }

        // Fixed names, so re-exporting replaces rather than accumulating. A share target that
        // still holds the previous uri sees it rewritten underneath — a small window, since
        // apps copy an attachment as they attach it.
        val sets = File(dir, "liftlog-export.csv").apply { writeText(buildCsv(details, weeks)) }
        val fatigue = File(dir, "liftlog-fatigue.csv").apply { writeText(buildFatigueCsv(weeks)) }

        return arrayListOf(uriFor(context, sets), uriFor(context, fatigue))
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * A chooser that sends both files at once.
     *
     * `ACTION_SEND_MULTIPLE` with an `ArrayList<Uri>` specifically: the framework's
     * `migrateExtraStreamToClipData` has a branch for that action which builds a multi-item
     * ClipData from the list and carries the read grant onto every uri. It reads the extra
     * back as a parcelable *array list*, so a plain List would be ignored, and it bails out
     * early if a ClipData is already set — so building one by hand here would lose the grant
     * rather than help.
     *
     * The type stays `text/csv` rather than widening to a wildcard. The cost is that a share target
     * has to declare a SEND_MULTIPLE filter to appear at all, so an app that accepts one CSV
     * may not be offered for two.
     */
    fun chooserFor(uris: ArrayList<Uri>): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Lift Log export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Export training log"
        )
}
