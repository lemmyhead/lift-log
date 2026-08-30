package dev.stan.liftlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stan.liftlog.data.Repo
import dev.stan.liftlog.data.TrainingDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A clock that recomposes callers roughly twice a second. */
@Composable
fun rememberNow(periodMs: Long = 500L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

/** Every logged training, re-read whenever anything is written. */
@Composable
fun rememberAllDetails(): List<TrainingDetail> {
    val revision by Repo.revision.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf(emptyList<TrainingDetail>()) }
    LaunchedEffect(revision) {
        data = withContext(Dispatchers.IO) { Repo.allDetails() }
    }
    return data
}

@Composable
fun rememberDetail(trainingId: Long): TrainingDetail? {
    val revision by Repo.revision.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf<TrainingDetail?>(null) }
    LaunchedEffect(revision, trainingId) {
        data = withContext(Dispatchers.IO) { Repo.detail(trainingId) }
    }
    return data
}

@Composable
fun rememberNameSuggestions(): List<String> {
    val revision by Repo.revision.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(revision) {
        data = withContext(Dispatchers.IO) { Repo.exerciseNameSuggestions() }
    }
    return data
}

@Composable
fun StatTile(label: String, value: String, hint: String? = null, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(value, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
