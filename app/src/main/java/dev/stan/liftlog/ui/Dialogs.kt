package dev.stan.liftlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stan.liftlog.data.SetEntry
import dev.stan.liftlog.data.SetLabel
import dev.stan.liftlog.data.formatClock
import dev.stan.liftlog.data.formatWeight

private val restPresets = listOf(45, 60, 90, 120, 150, 180, 210, 240, 300, 360, 420)

@Composable
fun AddExerciseDialog(onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var copyPlan by remember { mutableStateOf(true) }
    val suggestions = rememberNameSuggestions()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Movement") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ChipRow(suggestions.take(12)) { name = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = copyPlan, onCheckedChange = { copyPlan = it })
                    Text(
                        "Bring back last session's ramp",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), copyPlan) },
                enabled = name.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Writes one rung of the ramp. Weight, reps and rest all belong to the individual set —
 * a 100 kg warm-up and a 160 kg working single want nothing in common.
 */
@Composable
fun PlannedSetDialog(
    title: String,
    exerciseName: String,
    initialReps: Int,
    initialWeight: Double,
    initialRest: Int,
    initialLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Int, Double, Int, String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var reps by remember { mutableStateOf(initialReps.toString()) }
    var weight by remember { mutableStateOf(formatWeight(initialWeight)) }
    var rest by remember { mutableStateOf(initialRest) }
    var label by remember { mutableStateOf(initialLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (exerciseName.isNotBlank()) {
                    Text(exerciseName, style = MaterialTheme.typography.bodyMedium)
                }
                RepsWeightFields(
                    reps = reps, onReps = { reps = it },
                    weight = weight, onWeight = { weight = it }
                )

                Text(
                    "Rest after this set — ${if (rest > 0) formatClock(rest) else "no alarm"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(restPresets.map { formatClock(it) } + "off") { picked ->
                    rest = if (picked == "off") 0 else restPresets.first { formatClock(it) == picked }
                }

                Text(
                    "Kind of set",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(SetLabel.presets + "none") { picked ->
                    label = if (picked == "none") "" else picked
                }
                if (label.isNotBlank()) {
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }

                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Remove this set", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    reps.toIntOrNull() ?: initialReps,
                    weight.replace(',', '.').toDoubleOrNull() ?: initialWeight,
                    rest,
                    label
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Corrects what actually happened, once a set is done. */
@Composable
fun EditSetDialog(
    set: SetEntry,
    number: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Double, Double?) -> Unit,
    onDelete: () -> Unit
) {
    var reps by remember { mutableStateOf(set.reps.toString()) }
    var weight by remember { mutableStateOf(formatWeight(set.weight)) }
    var rpe by remember { mutableStateOf(set.rpe?.let { formatWeight(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set $number — what you did") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Planned ${formatWeight(set.plannedWeight)} kg × ${set.plannedReps}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RepsWeightFields(
                    reps = reps, onReps = { reps = it },
                    weight = weight, onWeight = { weight = it }
                )
                OutlinedTextField(
                    value = rpe,
                    onValueChange = { rpe = it },
                    label = { Text("RPE 1–10 (optional)") },
                    isError = rpe.isNotBlank() &&
                        rpe.replace(',', '.').toDoubleOrNull()?.let { it !in 1.0..10.0 } != false,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onDelete, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Delete this set", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    reps.toIntOrNull() ?: set.reps,
                    weight.replace(',', '.').toDoubleOrNull() ?: set.weight,
                    // Off-scale means a typo — a 75 meant as 7.5 would otherwise sit in the
                    // log and drag a whole week's average RPE past the deload threshold.
                    rpe.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..10.0 }
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RepsWeightFields(
    reps: String,
    onReps: (String) -> Unit,
    weight: String,
    onWeight: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = weight,
            onValueChange = onWeight,
            label = { Text("Weight kg") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = reps,
            onValueChange = onReps,
            label = { Text("Reps") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Wraps rather than scrolls: a preset you cannot see is a preset you will not use, and the
 * rest targets run to eleven options.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(options: List<String>, onPick: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            AssistChip(onClick = { onPick(option) }, label = { Text(option) })
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
