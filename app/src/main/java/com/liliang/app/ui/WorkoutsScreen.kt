package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.ProgressionRole
import com.liliang.app.data.WorkoutTemplateItem
import com.liliang.app.data.formatDistance
import com.liliang.app.data.formatDuration
import com.liliang.app.data.parseLeadingDouble
import com.liliang.app.data.parseLeadingInt
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton
import com.liliang.app.design.Stamp

/**
 * Saved workouts (templates): the routines you assign to plan days or start
 * from. List, create, edit. Reached from Settings.
 */
@Composable
fun WorkoutsScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val templates = viewModel.plannerSnapshot.templates
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        item("top") {
            EditorHeader(
                "Workouts",
                subtitle = "套 · your saved routines",
                onBack = onBack,
                action = { SealButton("New", hanzi = "立", compact = true, onClick = { viewModel.newTemplate() }) },
            )
        }
        if (templates.isEmpty()) {
            item("empty") {
                InkText("No saved workouts yet. Build one here, or use \u201cSave as workout\u201d on any finished workout\u2019s record.", style = InkType.BodyDim, color = InkColors.BoneFaint)
            }
        }
        items(templates.size) { i ->
            val t = templates[i]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.card)
                    .background(InkColors.PaperRaised)
                    .border(1.dp, InkColors.Line, InkShapes.card)
                    .clickable { viewModel.editTemplate(t) }
                    .padding(InkSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stamp("练", tint = InkColors.BoneDim, size = 30.dp)
                Spacer(Modifier.width(InkSpacing.m))
                Column(Modifier.weight(1f)) {
                    InkText(t.name.ifBlank { "Workout" }, style = InkType.Heading)
                    InkText(pluralize(t.items.size, "exercise"), style = InkType.Label, color = InkColors.BoneFaint)
                }
                InkText("→", style = InkType.Body, color = InkColors.BoneFaint)
            }
        }
    }
}

@Composable
fun WorkoutEditorScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val template = viewModel.plannerTemplate
    val config = viewModel.configSnapshot
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader(if (template.id == 0L) "New workout" else "Edit workout", onBack = onBack)
        }
        item("name") {
            InkTextField(label = "Name", value = template.name, onValueChange = { v -> viewModel.updatePlannerTemplate { it.copy(name = v) } })
        }
        item("ex-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkText("EXERCISES", style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.weight(1f))
                GhostButton("+ Add", onClick = { showAdd = true })
            }
        }
        items(template.items.size, key = { template.items[it].id }) { i ->
            val tItem = template.items[i]
            TemplateItemCard(
                item = tItem,
                canUp = i > 0,
                canDown = i < template.items.size - 1,
                onMove = { delta -> viewModel.movePlannerItem(tItem.id, delta) },
                onRole = { role -> viewModel.updatePlannerItem(tItem.id) { it.copy(progressionRole = role) } },
                onSets = { v -> viewModel.updatePlannerItem(tItem.id) { it.copy(setsTarget = parseLeadingInt(v)) } },
                onReps = { v -> viewModel.updatePlannerItem(tItem.id) { it.copy(repsTarget = v) } },
                onWeight = { v -> viewModel.updatePlannerItem(tItem.id) { it.copy(weightTarget = parseLeadingDouble(v)) } },
                onDuration = { s -> viewModel.updatePlannerItem(tItem.id) { it.copy(durationTargetSeconds = s) } },
                onRemove = { viewModel.removePlannerItem(tItem.id) },
            )
        }
        item("actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                SealButton("Save workout", hanzi = "存", enabled = template.name.isNotBlank(), onClick = { viewModel.savePlannerTemplate() })
                if (template.id != 0L) GhostButton("Delete", onClick = { pendingDelete = true }, tint = InkColors.Vermillion)
            }
        }
    }

    if (showAdd) {
        SearchSelectionDialog(
            title = "Add exercise",
            options = orderedExerciseOrMuscleOptions(config.exercises, config.muscleGroups),
            allowCustomEntry = true,
            customLabel = "Exercise or muscle group",
            onDismiss = { showAdd = false },
            onSelect = { name ->
                viewModel.addPlannerExercise(name)
                showAdd = false
            },
        )
    }
    if (pendingDelete) {
        ConfirmationDialog(
            title = "Delete workout?",
            message = "Removes this saved workout. Plan days using it fall back to rest.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = false },
            onConfirm = {
                viewModel.deletePlannerTemplate(template.id)
                pendingDelete = false
            },
        )
    }
}

@Composable
private fun TemplateItemCard(
    item: WorkoutTemplateItem,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onRole: (ProgressionRole) -> Unit,
    onSets: (String) -> Unit,
    onReps: (String) -> Unit,
    onWeight: (String) -> Unit,
    onDuration: (Int?) -> Unit,
    onRemove: () -> Unit,
) {
    var showHold by androidx.compose.runtime.saveable.rememberSaveable(item.id) { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.card)
            .background(InkColors.PaperRaised)
            .border(1.dp, InkColors.Line, InkShapes.card)
            .padding(InkSpacing.l),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText(item.title.ifBlank { "Exercise" }, style = InkType.Heading, modifier = Modifier.weight(1f), maxLines = 1)
            ReorderHandles(canUp = canUp, canDown = canDown, onUp = { onMove(-1) }, onDown = { onMove(1) })
            Box(
                Modifier.size(44.dp).clip(InkShapes.chip).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { InkText("✕", style = InkType.Body, color = InkColors.BoneFaint) }
        }
        if (item.progressionRole == ProgressionRole.RUNNING) {
            // A RUNNING item's role is structural — a chip tap must not
            // silently turn a run into a lift.
            InkText("RUNNING — paced by the engine's run planner", style = InkType.LabelSmall, color = InkColors.BoneFaint)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                // Auto (NONE) is a real state — the chips used to offer no way
                // back to it once any role was tapped.
                listOf(ProgressionRole.PRIMARY, ProgressionRole.SECONDARY, ProgressionRole.ACCESSORY, ProgressionRole.NONE).forEach { role ->
                    InkChip(
                        text = role.label,
                        selected = item.progressionRole == role,
                        onClick = { onRole(role) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            InkDecimalField(key = "sets:${item.id}", label = "Sets", value = item.setsTarget?.toString().orEmpty(), modifier = Modifier.weight(1f), onValueChange = onSets)
            InkTextField(label = "Reps", value = item.repsTarget, modifier = Modifier.weight(1f), onValueChange = onReps)
            InkDecimalField(key = "wt:${item.id}", label = "kg", value = editableDouble(item.weightTarget), modifier = Modifier.weight(1f), onValueChange = onWeight)
        }
        // Duration/distance targets (timed holds, ingested run items) were
        // invisible here — three empty fields looked broken while the real
        // targets hid. Holds are editable; distance shows read-only.
        if (item.durationTargetSeconds != null || item.distanceTargetKm != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l), verticalAlignment = Alignment.CenterVertically) {
                item.durationTargetSeconds?.let { dur ->
                    InkPickerPill(label = "Hold", value = formatDuration(dur), onClick = { showHold = true })
                }
                item.distanceTargetKm?.let { km ->
                    Column {
                        InkText("DISTANCE", style = InkType.LabelSmall, color = InkColors.BoneFaint)
                        InkText(formatDistance(km), style = InkType.NumberSmall, color = InkColors.BoneDim)
                    }
                }
            }
        }
    }
    if (showHold) {
        DurationPickerDialog(
            title = "Hold time",
            totalSeconds = item.durationTargetSeconds,
            onDismiss = { showHold = false },
            onConfirm = { s -> onDuration(s); showHold = false },
        )
    }
}
