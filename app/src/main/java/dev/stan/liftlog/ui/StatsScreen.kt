package dev.stan.liftlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stan.liftlog.data.Analytics
import dev.stan.liftlog.data.Export
import dev.stan.liftlog.data.TrainingDetail
import dev.stan.liftlog.data.formatDay
import dev.stan.liftlog.data.formatTonnage
import dev.stan.liftlog.data.formatWeight
import dev.stan.liftlog.data.plural
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val all = rememberAllDetails().filter { it.training.endedAt != null }
    val weeks = Analytics.weeks(all, count = 12)
    val trends = Analytics.trends(all)
    val ratio = Analytics.acwr(all)
    val thisWeek = weeks.lastOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Load & fatigue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { context.startActivity(Export.shareIntent(context, all)) },
                        enabled = all.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export CSV")
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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (all.isEmpty()) {
                item {
                    Text(
                        "Finish a training session and the load numbers show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 40.dp)
                    )
                }
                return@LazyColumn
            }

            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = "This week",
                        value = formatTonnage(thisWeek?.tonnage ?: 0.0),
                        hint = "${plural(thisWeek?.sets ?: 0, "set")} · ${plural(thisWeek?.sessions ?: 0, "session")}",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Load ratio",
                        value = ratio?.let { "%.2f".format(it) } ?: "—",
                        hint = ratio?.let { Analytics.acwrVerdict(it) } ?: "needs 4 weeks",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Last session",
                        value = Analytics.daysSinceLast(all)?.let { if (it == 0L) "today" else "${it}d" } ?: "—",
                        hint = "ago",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item { SectionHeader("Weekly tonnage", "last 12 weeks") }
            item { WeeklyChart(weeks) }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Load ratio compares this week's tonnage with your rolling four-week average. " +
                            "0.8–1.3 is steady progression, above 1.5 is the spike that usually precedes a " +
                            "forced rest, and a deliberate drop below 0.8 is your deload week.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            item { SectionHeader("Movements", "estimated 1RM") }
            trends.forEach { trend ->
                item(key = "trend-${trend.name}") { TrendCard(trend) }
            }

            item { Box(Modifier.height(32.dp)) {} }
        }
    }
}

@Composable
private fun WeeklyChart(weeks: List<Analytics.Week>) {
    val max = weeks.maxOfOrNull { it.tonnage }?.coerceAtLeast(1.0) ?: 1.0
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth().height(140.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                weeks.forEachIndexed { index, week ->
                    val isCurrent = index == weeks.lastIndex
                    val fraction = (week.tonnage / max).toFloat()
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((124.dp * fraction).coerceAtLeast(3.dp))
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                )
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                weeks.forEachIndexed { index, week ->
                    Text(
                        if (index % 3 == 0 || index == weeks.lastIndex) week.label.substringBefore(' ') else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(
                "peak ${formatTonnage(max)} in a week",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TrendCard(trend: Analytics.MovementTrend) {
    val latest = trend.latest ?: return
    val best = trend.best
    val delta = trend.delta

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(trend.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${latest.est1rm.roundToInt()} kg",
                    style = NumberStyle,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "top set ${formatWeight(latest.bestSet.weight)} kg × ${latest.bestSet.reps} · ${formatDay(latest.at)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (delta != null) {
                    Pill(
                        (if (delta >= 0) "+" else "") + "%.1f%% vs last".format(delta * 100)
                    )
                }
                best?.let { Pill("best ${it.est1rm.roundToInt()} kg") }
                Pill(plural(trend.points.size, "session"))
            }
        }
    }
}
