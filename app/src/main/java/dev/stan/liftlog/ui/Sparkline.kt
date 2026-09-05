package dev.stan.liftlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A bare trend line, drawn rather than charted.
 *
 * There is no charting library on the classpath and this does not justify adding one: the whole
 * shape is a polyline and an optional shaded band, against a Canvas API already present.
 *
 * A null in [values] is a week with no measurement, and it **breaks the line** rather than
 * plotting at zero — the same distinction the metrics themselves keep. A run of gaps therefore
 * shows as a gap, which is the honest picture of a month with no grip readings.
 *
 * [band] shades the range a value is supposed to sit in, so "is this normal" is answered by
 * where the line is rather than by reading the axis.
 */
@Composable
fun Sparkline(
    values: List<Double?>,
    line: Color,
    modifier: Modifier = Modifier,
    band: ClosedFloatingPointRange<Double>? = null,
    bandColor: Color = line.copy(alpha = 0.14f),
    baseline: Double? = null,
    baselineColor: Color = line.copy(alpha = 0.35f),
    height: Dp = 56.dp
) {
    val points = values.filterNotNull()
    if (points.isEmpty()) return

    // The band is part of the scale, not an overlay on it: leave it out and a line sitting
    // wholly inside the band would rescale to fill the box and look like wild variation.
    var lo = minOf(points.min(), band?.start ?: Double.MAX_VALUE, baseline ?: Double.MAX_VALUE)
    var hi = maxOf(points.max(), band?.endInclusive ?: -Double.MAX_VALUE, baseline ?: -Double.MAX_VALUE)
    if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }   // a perfectly flat series still needs a scale
    val pad = (hi - lo) * 0.12
    lo -= pad; hi += pad

    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        fun y(v: Double): Float = (h * (1.0 - (v - lo) / (hi - lo))).toFloat()
        // One point would divide by zero; centre it instead.
        fun x(i: Int): Float =
            if (values.size <= 1) w / 2f else w * i.toFloat() / (values.size - 1).toFloat()

        band?.let {
            val top = y(it.endInclusive)
            drawRect(bandColor, topLeft = Offset(0f, top), size = Size(w, y(it.start) - top))
        }
        baseline?.let {
            drawLine(baselineColor, Offset(0f, y(it)), Offset(w, y(it)), strokeWidth = 1.dp.toPx())
        }

        // Each unbroken run is its own path, so a gap leaves no segment bridging it.
        var path: Path? = null
        values.forEachIndexed { i, v ->
            if (v == null) {
                path?.let { drawPath(it, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
                path = null
                return@forEachIndexed
            }
            val px = x(i)
            val py = y(v)
            if (path == null) {
                path = Path().apply { moveTo(px, py) }
                // A lone reading between two gaps has no line to be part of, so mark it.
                if (values.getOrNull(i - 1) == null && values.getOrNull(i + 1) == null) {
                    drawCircle(line, radius = 2.dp.toPx(), center = Offset(px, py))
                }
            } else {
                path?.lineTo(px, py)
            }
        }
        path?.let { drawPath(it, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
    }
}
