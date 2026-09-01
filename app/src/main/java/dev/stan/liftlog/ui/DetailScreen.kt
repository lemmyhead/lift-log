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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stan.liftlog.data.Repo
import dev.stan.liftlog.data.SetEntry
import dev.stan.liftlog.data.formatClock
import dev.stan.liftlog.data.formatDayTime
import dev.stan.liftlog.data.formatShort
import dev.stan.liftlog.data.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(trainingId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val detail = rememberDetail(trainingId)
    var editing by remember { mutableStateOf<SetEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    LaunchedEffect(detail?.training?.id) {
        detail?.let { notes = it.training.notes }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.training?.startedAt?.let { formatDayTime(it) } ?: "Session",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete session")
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
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile("Duration", formatClock(d.training.durationSeconds), modifier = Modifier.weight(1f).fillMaxHeight())
                    StatTile("Reps", d.totalReps.toString(), plural(d.setCount, "set"), modifier = Modifier.weight(1f).fillMaxHeight())
                    StatTile("Avg rest", formatShort(d.avgRestSeconds), modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }

            d.exercises.forEach { ex ->
                item(key = "ex-${ex.exercise.id}") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ex.exercise.name, style = MaterialTheme.typography.titleMedium)
                            ex.completed.forEach { set -> SetRow(set) { editing = set } }
                            Text(
                                plural(ex.totalReps, "rep"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = {
                        notes = it
                        scope.launch(Dispatchers.IO) { Repo.setNotes(trainingId, it) }
                    },
                    label = { Text("Session notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            item { Box(Modifier.height(32.dp)) {} }
        }
    }

    editing?.let { set ->
        EditSetDialog(
            set = set,
            onDismiss = { editing = null },
            onSave = { reps, weight, rpe ->
                editing = null
                scope.launch(Dispatchers.IO) { Repo.updateSet(set.id, reps, weight, rpe) }
            },
            onDelete = {
                editing = null
                scope.launch(Dispatchers.IO) { Repo.deleteSet(set.id) }
            }
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this session?",
            body = "Every exercise and set recorded in it is removed. This cannot be undone.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch(Dispatchers.IO) { Repo.deleteTraining(trainingId) }
                onBack()
            }
        )
    }
}
