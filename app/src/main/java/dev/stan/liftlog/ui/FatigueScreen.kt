package dev.stan.liftlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stan.liftlog.data.Fatigue
import dev.stan.liftlog.data.ReadinessLevel
import dev.stan.liftlog.data.WeekMetrics
import dev.stan.liftlog.data.plural

/** "—" for a measurement that was never taken; never a zero standing in for one. */
private fun show(v: Double?, decimals: Int = 1, suffix: String = ""): String =
    if (v == null) "—" else "%.${decimals}f".format(v) + suffix

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FatigueScreen(onBack: () -> Unit) {
    val details = rememberAllDetails()
    // Keyed on the log rather than on a clock: this walks every session, and the screen has no
    // reason to recompute it on a ticker. rememberAllDetails re-reads on any write, so a grip
    // reading typed on the session screen lands here immediately.
    val weeks = remember(details) { Fatigue.weekly(details) }
    val verdict = remember(weeks) { Fatigue.verdict(weeks) }
    val current = remember(weeks) { Fatigue.current(weeks) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fatigue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (weeks.isEmpty() || current == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing to measure yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Train a few weeks, rate your working sets, and squeeze a grip " +
                            "meter before you lift. Four weeks in, this starts telling you " +
                            "something you did not already know.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { VerdictCard(verdict.level, verdict.headline, verdict.reasons) }

            item {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        "Load ratio", show(current.acwr, 2), "target 0.80–1.30",
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    StatTile(
                        "Chronic RPE", show(current.chronicRpe),
                        "this week ${show(current.avgRpe)}",
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    StatTile(
                        "Grip", show(current.gripReadinessPct, 0, "%"), "target ~100%",
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }

            item {
                Trend(
                    "Load ratio",
                    "This week's tonnage against the four-week average. The band is the range " +
                        "where the work is being absorbed.",
                    weeks.map { it.acwr },
                    MaterialTheme.colorScheme.primary,
                    band = 0.80..1.30
                )
            }
            item {
                Trend(
                    "Effort",
                    "Weekly average RPE against its own four-week baseline. The line pulling " +
                        "away from the flat one means the same work is costing more.",
                    weeks.map { it.avgRpe },
                    MaterialTheme.colorScheme.error,
                    baseline = current.chronicRpe
                )
            }
            item {
                Trend(
                    "Grip readiness",
                    "Today's squeeze as a share of your four-week baseline. Below 95% is " +
                        "systemic fatigue rather than a bad day.",
                    weeks.map { it.gripReadinessPct },
                    MaterialTheme.colorScheme.secondary,
                    band = 95.0..105.0
                )
            }

            item { SectionHeader("By week", plural(weeks.count { it.hasTraining }, "trained week")) }
            for (m in weeks.asReversed()) {
                item(key = "wk-${m.week.label}") { WeekRow(m) }
            }

            item {
                Text(
                    "Volume counts loaded work only — bodyweight sets at zero weight add " +
                        "nothing, so a week of them reads here as a rest week. Effort averages " +
                        "every set you rated, weighted by how many you did. Grip is whatever " +
                        "you measured before lifting.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item { Box(Modifier.height(32.dp)) {} }
        }
    }
}

@Composable
private fun VerdictCard(level: ReadinessLevel, headline: String, reasons: List<String>) {
    val container = when (level) {
        ReadinessLevel.DELOAD -> MaterialTheme.colorScheme.error
        ReadinessLevel.WATCH -> MaterialTheme.colorScheme.primaryContainer
        ReadinessLevel.OK -> MaterialTheme.colorScheme.secondary
        ReadinessLevel.INSUFFICIENT -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (level) {
        ReadinessLevel.DELOAD -> MaterialTheme.colorScheme.onError
        ReadinessLevel.WATCH -> MaterialTheme.colorScheme.onPrimaryContainer
        ReadinessLevel.OK -> MaterialTheme.colorScheme.onSecondary
        ReadinessLevel.INSUFFICIENT -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                level.name,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.75f)
            )
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = content
            )
            reasons.forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

@Composable
private fun Trend(
    title: String,
    caption: String,
    values: List<Double?>,
    color: Color,
    band: ClosedFloatingPointRange<Double>? = null,
    baseline: Double? = null
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (values.none { it != null }) {
                Text(
                    "Nothing measured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Sparkline(values = values, line = color, band = band, baseline = baseline)
            }
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeekRow(m: WeekMetrics) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor =
                if (m.hasTraining) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("W${m.weekNumber}", style = NumberStyle, color = if (m.hasTraining) MaterialTheme.colorScheme.onSurface else muted)
                Text(m.week.label, style = MaterialTheme.typography.labelSmall, color = muted)
            }
            if (!m.hasTraining) {
                Text("rest week", style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                Text(
                    "${show(m.weeklyVolumeKg, 0)} kg · ACWR ${show(m.acwr, 2)} · " +
                        "RPE ${show(m.avgRpe)} · grip ${show(m.gripReadinessPct, 0, "%")}",
                    style = NumberStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Shown so every number above can be traced back to what it was built from.
                Text(
                    "${plural(m.sessionCount, "session")} · ${plural(m.setCount, "set")} · " +
                        "${plural(m.rpeSampleCount, "rating")} · ${plural(m.gripSampleCount, "grip reading")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )
            }
        }
    }
}
