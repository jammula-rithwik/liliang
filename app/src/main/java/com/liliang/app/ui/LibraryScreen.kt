package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.ExerciseCatalogItem
import com.liliang.app.Overlay
import com.liliang.app.data.ComboMove
import com.liliang.app.data.LogFormat
import com.liliang.app.data.MovementType
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton

/**
 * The library: every exercise and every combo, each tappable into its
 * editor. Reached from Settings.
 */
@Composable
fun LibraryScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val config = viewModel.configSnapshot
    var query by rememberSaveable { mutableStateOf("") }
    val exercises = config.exercises
        .filter { query.isBlank() || it.name.contains(query, true) }
        .sortedBy { it.name.lowercase() }
    val combos = config.combos.filter { query.isBlank() || it.name.contains(query, true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        item("top") {
            EditorHeader("Library", subtitle = "库 · exercises & combos", onBack = onBack) {
                Spacer(Modifier.height(InkSpacing.xs))
                InkTextField(label = "Search", value = query, onValueChange = { query = it })
            }
        }
        item("ex-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkText("EXERCISES", style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.weight(1f))
                GhostButton("+ New", onClick = {
                    viewModel.loadExerciseEditor(null)
                    viewModel.pushOverlay(Overlay.ExerciseEditor)
                })
            }
        }
        items(exercises.size) { i ->
            val ex = exercises[i]
            LibraryRow(
                title = ex.name,
                subtitle = listOf(ex.defaultMuscle, ex.defaultCategory).filter { it.isNotBlank() }.joinToString(" · "),
                hanzi = "铁",
                onClick = {
                    viewModel.loadExerciseEditor(ex)
                    viewModel.pushOverlay(Overlay.ExerciseEditor)
                },
            )
        }
        item("combo-label") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = InkSpacing.m)) {
                InkText("COMBOS", style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.weight(1f))
                GhostButton("+ New", onClick = {
                    viewModel.loadComboEditor(null)
                    viewModel.pushOverlay(Overlay.ComboEditor)
                })
            }
        }
        items(combos.size) { i ->
            val combo = combos[i]
            LibraryRow(
                title = combo.name,
                subtitle = listOf(combo.discipline, pluralize(combo.moves.size, "move")).filter { it.isNotBlank() }.joinToString(" · "),
                hanzi = "武",
                onClick = {
                    viewModel.loadComboEditor(combo)
                    viewModel.pushOverlay(Overlay.ComboEditor)
                },
            )
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, hanzi: String, onClick: () -> Unit) {
    InkListRow(title, subtitle = subtitle, hanzi = hanzi, onClick = onClick)
}

@Composable
fun ExerciseEditorScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val ex = viewModel.exerciseEditor
    val config = viewModel.configSnapshot
    var pendingDelete by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader(if (ex.id == 0L && ex.name.isBlank()) "New exercise" else "Edit exercise", onBack = onBack)
        }
        item("fields") {
            ExerciseFields(
                item = ex,
                muscles = config.muscleGroups,
                categories = config.categories,
                onChange = { updated -> viewModel.updateExerciseEditor { updated } },
            )
        }
        item("actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                SealButton("Save", hanzi = "存", enabled = ex.name.isNotBlank() && ex.defaultMuscle.isNotBlank(), onClick = { viewModel.saveExerciseEditor() })
                if (ex.id != 0L) GhostButton("Delete", onClick = { pendingDelete = true }, tint = InkColors.Vermillion)
            }
        }
    }

    if (pendingDelete) {
        ConfirmationDialog(
            title = "Delete ${ex.name.ifBlank { "exercise" }}?",
            message = "Removes it from the library. Logged history keeps its rows.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = false },
            onConfirm = {
                viewModel.deleteExercise(ex.name)
                pendingDelete = false
            },
        )
    }
}

/**
 * The one exercise field set — shared by the library editor and the in-session
 * "save this new exercise" dialog so the two can't drift (F7).
 */
@Composable
fun ExerciseFields(
    item: ExerciseCatalogItem,
    muscles: List<String>,
    categories: List<String>,
    onChange: (ExerciseCatalogItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
        InkTextField(label = "Name", value = item.name, onValueChange = { v -> onChange(item.copy(name = v)) })
        InkDropdownField(label = "Muscle", selectedValue = item.defaultMuscle.takeIf { it.isNotBlank() }, options = muscles, onSelect = { v -> onChange(item.copy(defaultMuscle = v)) })
        InkDropdownField(label = "Category", selectedValue = item.defaultCategory.takeIf { it.isNotBlank() }, options = categories, onSelect = { v -> onChange(item.copy(defaultCategory = v)) })
        Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            InkText("LOGGED AS", style = InkType.Label, color = InkColors.BoneFaint)
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                listOf(LogFormat.REPS to "Reps", LogFormat.SECONDS to "Time", LogFormat.MINUTES to "Minutes").forEach { (fmt, label) ->
                    InkChip(text = label, selected = item.logFormat == fmt, onClick = { onChange(item.copy(logFormat = fmt)) })
                }
            }
        }
        InkChip(text = "Bodyweight", selected = item.isBodyweight, onClick = { onChange(item.copy(isBodyweight = !item.isBodyweight)) })
        Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            InkText("MOVEMENT", style = InkType.Label, color = InkColors.BoneFaint)
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                MovementType.entries.forEach { type ->
                    InkChip(text = type.label, selected = item.movementType == type, onClick = { onChange(item.copy(movementType = type)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComboEditorScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val combo = viewModel.comboEditor
    val config = viewModel.configSnapshot
    var pendingDelete by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader(if (combo.id == 0L) "New combo" else "Edit combo", onBack = onBack)
        }
        item("fields") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                InkTextField(label = "Name", value = combo.name, onValueChange = { v -> viewModel.updateComboEditor { it.copy(name = v) } })
                InkDropdownField(label = "Discipline", selectedValue = combo.discipline.takeIf { it.isNotBlank() }, options = config.disciplines, onSelect = { v -> viewModel.updateComboEditor { it.copy(discipline = v) } })
                InkTextField(label = "Notes (unusual moves, cues)", value = combo.notes, singleLine = false, minLines = 2, onValueChange = { v -> viewModel.updateComboEditor { it.copy(notes = v) } })
            }
        }
        // Current sequence — tap a move to drop it.
        item("sequence") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("SEQUENCE · tap to remove", style = InkType.Label, color = InkColors.BoneFaint)
                if (combo.moves.isEmpty()) {
                    InkText("Tap moves below to build the combo.", style = InkType.BodyDim, color = InkColors.BoneFaint)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s), verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                        combo.moves.forEachIndexed { index, move ->
                            Box(
                                modifier = Modifier
                                    .clip(InkShapes.chip)
                                    .background(InkColors.Vermillion.copy(alpha = 0.16f))
                                    .border(1.dp, InkColors.Vermillion.copy(alpha = 0.5f), InkShapes.chip)
                                    .clickable {
                                        viewModel.updateComboEditor { it.copy(moves = it.moves.toMutableList().also { m -> m.removeAt(index) }) }
                                    }
                                    .padding(horizontal = InkSpacing.m, vertical = 6.dp),
                            ) {
                                InkText("${index + 1}. ${move.displayName}", style = InkType.BodyDim, color = InkColors.Bone)
                            }
                        }
                    }
                }
            }
        }
        // Palette grouped by category.
        ComboMove.groupedByCategory().forEach { (category, moves) ->
            item("cat:${category.name}") {
                Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    InkText(category.label.uppercase(), style = InkType.Label, color = InkColors.BoneFaint)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s), verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                        moves.forEach { move ->
                            InkChip(
                                text = move.displayName,
                                selected = false,
                                onClick = { viewModel.updateComboEditor { it.copy(moves = it.moves + move) } },
                            )
                        }
                    }
                }
            }
        }
        item("actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                SealButton("Save", hanzi = "存", enabled = combo.name.isNotBlank(), onClick = { viewModel.saveComboEditor() })
                if (combo.id != 0L) GhostButton("Delete", onClick = { pendingDelete = true }, tint = InkColors.Vermillion)
            }
        }
    }
    if (pendingDelete) {
        ConfirmationDialog(
            title = "Delete ${combo.name.ifBlank { "combo" }}?",
            message = "Removes it from the library. Logged rounds keep their history.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = false },
            onConfirm = {
                viewModel.deleteCombo(combo.id)
                pendingDelete = false
            },
        )
    }
}
