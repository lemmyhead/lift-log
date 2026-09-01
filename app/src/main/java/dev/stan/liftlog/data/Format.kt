package dev.stan.liftlog.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val dayTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

fun formatClock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Compact form for dense rows: "2m31" rather than "2:31". */
fun formatShort(seconds: Int?): String {
    if (seconds == null) return "—"
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m${"%02d".format(s)}" else "${s}s"
}

fun formatDay(ms: Long): String =
    dayFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun formatDayTime(ms: Long): String =
    dayTimeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun formatTime(ms: Long): String =
    timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Count plus a correctly-inflected noun: "1 set", "3 sets". */
fun plural(count: Int, singular: String, pluralForm: String = singular + "s"): String =
    "$count ${if (count == 1) singular else pluralForm}"

fun formatWeight(w: Double): String =
    if (w == w.roundToInt().toDouble()) "${w.roundToInt()}" else "%.1f".format(w)
