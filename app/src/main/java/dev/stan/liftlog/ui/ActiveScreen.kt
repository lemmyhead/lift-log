package dev.stan.liftlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stan.liftlog.data.ExerciseWithSets
import dev.stan.liftlog.data.SetEntry
import dev.stan.liftlog.data.formatClock
import dev.stan.liftlog.data.formatShort
import dev.stan.liftlog.data.formatWeight
import dev.stan.liftlog.data.numberOfSet
import dev.stan.liftlog.data.plural
import dev.stan.liftlog.timer.Phase
import dev.stan.liftlog.timer.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val session by SessionManager.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val detail = rememberDetail(session.trainingId)

    // The set just finished stays in hand right through RACKED and REST — endSet copies the
    // state without touching currentSetId, and only beginSet reassigns it. That is what lets
    // the rating be given during the rest that follows the set it describes.
    val ratableSetId = session.currentSetId
        .takeIf { it > 0 && (session.phase == Phase.RACKED || session.phase == Phase.REST) }
    val ratedRpe = ratableSetId?.let { id ->
        detail?.exercises?.firstNotNullOfOrNull { ex -> ex.sets.firstOrNull { it.id == id } }?.rpe
    }

    var showAddExercise by remember { mutableStateOf(false) }
    var editingActuals by remember { mutableStateOf<SetEntry?>(null) }
    var editingPlan by remember { mutableStateOf<SetEntry?>(null) }
    var planningFor by remember { mutableStateOf<ExerciseWithSets?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Training", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${formatClock(session.trainingSeconds(now))} · " +
                                "${plural(detail?.setCount ?: 0, "set")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = { TextButton(onClick = { confirmFinish = true }) { Text("Finish") } },
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
            item {
                PhaseCard(
                    phase = session.phase,
                    title = session.phaseTitle,
                    actionLabel = session.actionLabel,
                    seconds = session.displaySeconds(now),
                    exercise = session.currentExercise,
                    setLabel = session.currentSetLabel,
                    totalRest = session.totalRestSeconds(now),
                    targetSeconds = session.restTargetSeconds,
                    remaining = session.restRemaining(now),
                    overrun = session.restOverrun(now),
                    // With the plan done the button ends the session instead — through the
                    // same confirmation the top bar's Finish uses, since on screen there is
                    // room to ask.
                    onAdvance = {
                        if (session.nothingToStart) confirmFinish = true
                        else SessionManager.advance(context)
                    },
                    rpe = ratedRpe,
                    onRpe = ratableSetId?.let { id ->
                        { value: Double? -> SessionManager.setRpe(context, id, value) }
                    }
                )
            }

            item {
                GripCard(
                    gripKg = detail?.training?.gripKg,
                    onSave = { SessionManager.setGrip(context, session.trainingId, it) }
                )
            }

            item {
                Text(
                    "The same button sits on your lock screen. Plan the ramp below — each set " +
                        "carries its own weight, reps and rest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            detail?.exercises?.forEach { ex ->
                item(key = "ex-${ex.exercise.id}") {
                    ExerciseCard(
                        item = ex,
                        currentSetId = session.currentSetId,
                        nextSetId = detail?.nextUp?.id ?: -1L,
                        onEditActuals = { editingActuals = it },
                        onEditPlan = { editingPlan = it },
                        onPlanSet = { planningFor = ex }
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { showAddExercise = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Add exercise")
                }
            }

            item { Box(Modifier.height(32.dp)) {} }
        }
    }

    if (showAddExercise) {
        AddExerciseDialog(
            onDismiss = { showAddExercise = false },
            onConfirm = { name, copyPlan ->
                showAddExercise = false
                SessionManager.addExercise(context, name, copyPlan)
            }
        )
    }

    planningFor?.let { ex ->
        val previous = ex.sets.lastOrNull()
        PlannedSetDialog(
            title = "Plan a set",
            exerciseName = ex.exercise.name,
            initialReps = previous?.reps ?: 5,
            initialWeight = previous?.weight ?: 0.0,
            initialRest = previous?.restTargetSeconds ?: ex.exercise.restTargetSeconds,
            initialLabel = previous?.label.orEmpty(),
            onDismiss = { planningFor = null },
            onConfirm = { reps, weight, rest, label ->
                planningFor = null
                SessionManager.planSet(context, ex.exercise.id, reps, weight, rest, label)
            }
        )
    }

    editingPlan?.let { set ->
        PlannedSetDialog(
            title = "Set ${detail.numberOfSet(set)}",
            exerciseName = detail?.exercises
                ?.firstOrNull { it.exercise.id == set.exerciseId }?.exercise?.name.orEmpty(),
            initialReps = set.plannedReps,
            initialWeight = set.plannedWeight,
            initialRest = set.restTargetSeconds,
            initialLabel = set.label,
            onDismiss = { editingPlan = null },
            onDelete = {
                editingPlan = null
                SessionManager.deleteSet(context, set.id)
            },
            onConfirm = { reps, weight, rest, label ->
                editingPlan = null
                SessionManager.updatePlannedSet(context, set.id, reps, weight, rest, label)
            }
        )
    }

    editingActuals?.let { set ->
        EditSetDialog(
            set = set,
            number = detail.numberOfSet(set),
            onDismiss = { editingActuals = null },
            onSave = { reps, weight, rpe ->
                editingActuals = null
                SessionManager.updateSetActuals(context, set.id, reps, weight, rpe)
            },
            onDelete = {
                editingActuals = null
                SessionManager.deleteSet(context, set.id)
            }
        )
    }

    if (confirmFinish) {
        ConfirmDialog(
            title = "Finish this training?",
            body = "The session is closed and moved into your log. Any set still running is " +
                "saved first; sets you planned but never did are kept as unfinished.",
            confirmLabel = "Finish",
            onDismiss = { confirmFinish = false },
            onConfirm = {
                confirmFinish = false
                SessionManager.endTraining(context)
                onDone()
            }
        )
    }
}

@Composable
private fun PhaseCard(
    phase: Phase,
    title: String,
    actionLabel: String,
    seconds: Int,
    exercise: String,
    setLabel: String,
    totalRest: Int,
    targetSeconds: Int,
    remaining: Int?,
    overrun: Int?,
    onAdvance: () -> Unit,
    rpe: Double?,
    /** Null outside the window where there is a finished set to rate. */
    onRpe: ((Double?) -> Unit)?
) {
    val accent = when (phase) {
        Phase.SET -> MaterialTheme.colorScheme.primary
        Phase.REST -> MaterialTheme.colorScheme.secondary
        Phase.RACKED -> MaterialTheme.colorScheme.onSurfaceVariant
        Phase.READY -> MaterialTheme.colorScheme.primary
    }

    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = accent)

            Text(
                formatClock(seconds),
                style = MaterialTheme.typography.displayLarge,
                fontSize = 64.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                listOf(exercise, setLabel).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "Add an exercise and plan your sets" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                phase == Phase.REST && overrun != null -> Text(
                    "${formatClock(overrun)} over your ${formatClock(targetSeconds)} target",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                phase == Phase.REST && remaining != null -> Text(
                    "${formatClock(remaining)} left of ${formatClock(targetSeconds)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
                phase == Phase.RACKED -> Text(
                    if (targetSeconds > 0) "Rest target ${formatClock(targetSeconds)}"
                    else "No alarm on this set",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Rest is logged from the rack, so show it running even before the countdown starts.
            if (phase == Phase.RACKED || phase == Phase.REST) {
                Text(
                    "logged rest ${formatClock(totalRest)} — since you racked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onRpe != null) {
                RpeChips(rpe = rpe, onPick = onRpe)
            }

            Button(
                onClick = onAdvance,
                modifier = Modifier.fillMaxWidth().height(68.dp).padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color(0xFF171205)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** The nine half-steps of the scale, as one-tap chips. */
private val rpeOptions = listOf(6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0, 9.5, 10.0)

/**
 * Rates the set just finished, without interrupting anything.
 *
 * Chips rather than a dialog on purpose: the rating is wanted on every working set, and a
 * modal in the middle of the cycle would be four taps a set and would have no answer for the
 * lock screen, which drives the same cycle with no UI at all. Here it sits inside the rest
 * that follows the set — the one minute of the session with nothing else to do.
 */
@Composable
private fun RpeChips(rpe: Double?, onPick: (Double?) -> Unit) {
    // Keyed on the stored value so recording one collapses the strip, and a later change
    // reopens it rather than leaving it permanently expanded.
    var open by remember(rpe) { mutableStateOf(rpe == null) }

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (open) {
            Text(
                "Last set — how hard?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChipRow(rpeOptions.map { formatWeight(it) }) { picked ->
                onPick(rpeOptions.first { formatWeight(it) == picked })
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RPE ${formatWeight(rpe ?: 0.0)}",
                    style = NumberStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { open = true }) { Text("change") }
            }
        }
    }
}

/**
 * The session's grip reading.
 *
 * Sits under the phase card rather than over it: the big button is what the screen is for, and
 * a form above it would push it down the page for the whole session. Collapsed to a single
 * line once the number is in.
 */
@Composable
private fun GripCard(gripKg: Double?, onSave: (Double?) -> Unit) {
    var editing by remember(gripKg) { mutableStateOf(gripKg == null) }
    var text by remember(gripKg) { mutableStateOf(gripKg?.let { formatWeight(it) } ?: "") }

    val parsed = text.replace(',', '.').toDoubleOrNull()
    val blank = text.isBlank()
    // Range checked here rather than in Repo: refusing a typo is the screen's job, whereas
    // storing 150 when the lifter typed 555 would be inventing a measurement.
    val plausible = parsed != null && parsed in 1.0..150.0

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!editing) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Grip ${formatWeight(gripKg ?: 0.0)} kg", style = NumberStyle)
                        Text(
                            "measured before lifting",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { editing = true }) { Text("Change") }
                }
            } else {
                Text(
                    "Pre-session readiness",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Grip (kg) — before you lift") },
                    singleLine = true,
                    isError = !blank && !plausible,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    when {
                        !blank && !plausible -> "That does not look like a grip reading — expected 1 to 150 kg."
                        // Said out loud because the number means a different thing taken
                        // afterwards, and a baseline mixing the two is worth nothing.
                        else -> "Squeeze before your first warm-up, not after. Skip it and the " +
                            "week simply has no reading."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!blank && !plausible) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.align(Alignment.End)) {
                    if (gripKg != null) {
                        TextButton(onClick = { onSave(null); editing = false }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { onSave(parsed); editing = false },
                        enabled = plausible
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    item: ExerciseWithSets,
    currentSetId: Long,
    nextSetId: Long,
    onEditActuals: (SetEntry) -> Unit,
    onEditPlan: (SetEntry) -> Unit,
    onPlanSet: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.exercise.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${item.completed.size}/${item.sets.size} sets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.sets.isEmpty()) {
                Text(
                    "No sets planned yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item.sets.forEachIndexed { index, set ->
                SetRow(
                    set = set,
                    number = index + 1,
                    isCurrent = set.id == currentSetId,
                    isNext = set.id == nextSetId,
                    onClick = { if (set.done) onEditActuals(set) else onEditPlan(set) }
                )
            }

            TextButton(onClick = onPlanSet, modifier = Modifier.align(Alignment.End)) {
                Text("+ Plan a set")
            }
        }
    }
}

@Composable
fun SetRow(
    set: SetEntry,
    number: Int,
    isCurrent: Boolean = false,
    isNext: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val done = set.done
    val background = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        done -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val foreground =
        if (done || isCurrent) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$number",
            style = NumberStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(Modifier.weight(1f)) {
            Text("${formatWeight(set.weight)} kg × ${set.reps}", style = NumberStyle, color = foreground)
            if (set.label.isNotBlank()) {
                Text(
                    set.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (done) {
                set.rpe?.let { Pill("RPE ${formatWeight(it)}") }
                Pill("work ${formatShort(set.workSeconds)}")
                Pill("rest ${formatShort(set.restSeconds)}")
            } else {
                if (isNext) Pill("next")
                Pill(
                    if (set.restTargetSeconds > 0) "rest ${formatClock(set.restTargetSeconds)}"
                    else "no alarm"
                )
            }
        }
    }
}
