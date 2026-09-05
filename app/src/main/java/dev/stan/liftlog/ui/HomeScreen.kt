package dev.stan.liftlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stan.liftlog.data.Export
import dev.stan.liftlog.data.TrainingDetail
import dev.stan.liftlog.data.formatClock
import dev.stan.liftlog.data.formatDayTime
import dev.stan.liftlog.data.formatShort
import dev.stan.liftlog.data.plural
import dev.stan.liftlog.timer.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartOrResume: () -> Unit,
    onOpenTraining: (Long) -> Unit,
    onOpenFatigue: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by SessionManager.state.collectAsStateWithLifecycle()
    val history = rememberAllDetails()
    val finished = history.filter { it.training.endedAt != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lift Log") },
                actions = {
                    IconButton(onClick = onOpenFatigue) {
                        Icon(Icons.Default.MonitorHeart, contentDescription = "Fatigue")
                    }
                    IconButton(
                        onClick = {
                            // Off the main thread: this builds the whole log into two strings
                            // and writes them, and the work grows with every session logged.
                            scope.launch {
                                val uris = withContext(Dispatchers.IO) {
                                    // One clock for both files, so their week series cannot
                                    // end a week apart across a Sunday midnight.
                                    Export.writeExports(context, finished, System.currentTimeMillis())
                                }
                                context.startActivity(Export.chooserFor(uris))
                            }
                        },
                        enabled = finished.isNotEmpty()
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!session.active) SessionManager.startTraining(context)
                    onStartOrResume()
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(
                    if (session.active) "  Resume session" else "  Start training",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (session.active) {
                item { LiveSessionCard(onClick = onStartOrResume) }
            }

            if (finished.isEmpty()) {
                item { EmptyState() }
            } else {
                item {
                    SectionHeader("Training log", plural(finished.size, "session"))
                }
                items(finished, key = { it.training.id }) { detail ->
                    TrainingRow(detail) { onOpenTraining(detail.training.id) }
                }
            }

            item { Box(Modifier.padding(bottom = 88.dp)) {} }
        }
    }
}

@Composable
private fun LiveSessionCard(onClick: () -> Unit) {
    val session by SessionManager.state.collectAsStateWithLifecycle()
    val now = rememberNow()

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    if (session.resting) "Resting now" else "Session in progress",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "${plural(session.setsThisSession, "set")} · ${formatClock(session.trainingSeconds(now))} elapsed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                formatClock(session.phaseSeconds(now)),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Start a training and use the notification\nbuttons to time your rest between sets.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun TrainingRow(detail: TrainingDetail, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatDayTime(detail.training.startedAt), style = MaterialTheme.typography.titleSmall)
                Text(
                    formatClock(detail.training.durationSeconds),
                    style = NumberStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                detail.exerciseNames.joinToString(" · ").ifBlank { "No exercises recorded" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(plural(detail.setCount, "set"))
                Pill(plural(detail.totalReps, "rep"))
                Pill("rest ${formatShort(detail.avgRestSeconds)}")
            }
        }
    }
}

@Composable
fun Pill(text: String) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
