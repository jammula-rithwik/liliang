package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.MartialArtsDiscipline
import com.liliang.app.data.MartialArtsMove
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton
import com.liliang.app.design.BrushHanzi

/**
 * Add or edit a form: its names + discipline, and an editable move list.
 * A form saved with no moves is tracked at the whole-form level (mark
 * mastered); add moves to get the per-move learning tracker.
 */
@Composable
fun FormEditorScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    // State lives in the ViewModel (loaded when the overlay was pushed), so a
    // rotation mid-edit keeps every half-typed field — audit #19.
    val editor = viewModel.formEditor
    val existing = editor.id.isNotBlank()
    val title = editor.title
    val shortTitle = editor.shortTitle
    val subtitle = editor.subtitle
    val discipline = editor.discipline
    val moves = editor.moves
    var pendingDelete by rememberSaveable { mutableStateOf(false) }

    val disciplines = viewModel.configSnapshot.disciplines

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader(if (!existing) "New form" else "Edit form", brushHanzi = "套路", onBack = onBack)
        }
        item("meta") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                InkTextField(label = "Title (characters)", value = title, onValueChange = { v -> viewModel.updateFormEditor { it.copy(title = v) } })
                InkTextField(label = "Short name (Latin)", value = shortTitle, onValueChange = { v -> viewModel.updateFormEditor { it.copy(shortTitle = v) } })
                InkTextField(label = "Subtitle", value = subtitle, onValueChange = { v -> viewModel.updateFormEditor { it.copy(subtitle = v) } })
                InkDropdownField(label = "Discipline", selectedValue = discipline.takeIf { it.isNotBlank() }, options = disciplines, onSelect = { v -> viewModel.updateFormEditor { it.copy(discipline = v) } })
            }
        }
        item("moves-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkText("MOVES · ${moves.size}", style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.weight(1f))
                GhostButton("+ Move", onClick = {
                    viewModel.updateFormEditor { it.copy(moves = it.moves + MartialArtsMove(index = it.moves.size + 1, name = "")) }
                })
            }
        }
        items(moves.size, key = { moves[it].index }) { i ->
            val move = moves[i]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                Box(Modifier.width(26.dp)) { InkText("${move.index}", style = InkType.NumberSmall.copy(fontSize = 14.sp), color = InkColors.Vermillion) }
                Box(Modifier.weight(1f)) {
                    InkTextField(label = "Move ${move.index}", value = move.name, onValueChange = { v ->
                        viewModel.updateFormEditor { f -> f.copy(moves = f.moves.toMutableList().also { it[i] = move.copy(name = v) }) }
                    })
                }
                ReorderHandles(
                    canUp = i > 0,
                    canDown = i < moves.size - 1,
                    onUp = {
                        viewModel.updateFormEditor { f ->
                            f.copy(moves = f.moves.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }.mapIndexed { idx, m -> m.copy(index = idx + 1) })
                        }
                    },
                    onDown = {
                        viewModel.updateFormEditor { f ->
                            f.copy(moves = f.moves.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }.mapIndexed { idx, m -> m.copy(index = idx + 1) })
                        }
                    },
                )
                Box(
                    Modifier.size(44.dp).clip(InkShapes.chip).clickable {
                        viewModel.updateFormEditor { f ->
                            f.copy(moves = f.moves.filterIndexed { idx, _ -> idx != i }.mapIndexed { idx, m -> m.copy(index = idx + 1) })
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { InkText("✕", style = InkType.Body, color = InkColors.BoneFaint) }
            }
        }
        item("actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                SealButton("Save form", hanzi = "存", enabled = title.isNotBlank(), onClick = {
                    viewModel.saveMartialArtsForm(
                        editor.copy(
                            title = title.trim(),
                            shortTitle = shortTitle.trim(),
                            subtitle = subtitle.trim(),
                            discipline = discipline.trim().ifBlank { "Tai Chi" },
                            moves = moves.filter { it.name.isNotBlank() },
                        ),
                    )
                    onBack()
                })
                if (existing) GhostButton("Delete", onClick = { pendingDelete = true }, tint = InkColors.Vermillion)
            }
        }
    }

    if (pendingDelete && existing) {
        ConfirmationDialog(
            title = "Delete form?",
            message = "Removes the form and its move list. Progress is lost.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = false },
            onConfirm = {
                viewModel.deleteMartialArtsForm(editor.id)
                pendingDelete = false
                onBack()
            },
        )
    }
}

/**
 * Add a discipline — the Kendo case. New arts get a card in the Hall and
 * can hold their own forms and combos.
 */
@Composable
fun DisciplineEditorScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    // VM-backed (loaded at push) — rotation keeps the half-typed art.
    val editor = viewModel.disciplineEditor
    val name = editor.name
    val glyph = editor.glyph
    val tagline = editor.tagline
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader("New art", subtitle = "门 · add a discipline to the hall", onBack = onBack)
        }
        item("preview") {
            if (glyph.isNotBlank() || name.isNotBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(InkShapes.card).background(InkColors.PaperRaised).border(1.dp, InkColors.Line, InkShapes.card).padding(InkSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(InkSpacing.xs),
                ) {
                    if (glyph.isNotBlank()) InkText(glyph, style = BrushHanzi(44.sp), color = InkColors.Bone, maxLines = 1)
                    InkText(name.ifBlank { "Name" }, style = InkType.Heading)
                    if (tagline.isNotBlank()) InkText(tagline, style = InkType.Label, color = InkColors.BoneFaint)
                }
            }
        }
        item("fields") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                InkTextField(label = "Name (e.g. Kendo)", value = name, onValueChange = { v -> viewModel.updateDisciplineEditor { it.copy(name = v) } })
                InkTextField(label = "Glyph (e.g. 剑道)", value = glyph, onValueChange = { v -> viewModel.updateDisciplineEditor { it.copy(glyph = v) } })
                InkTextField(label = "Tagline", value = tagline, onValueChange = { v -> viewModel.updateDisciplineEditor { it.copy(tagline = v) } })
            }
        }
        item("actions") {
            SealButton("Add art", hanzi = "立", enabled = name.isNotBlank(), onClick = {
                viewModel.saveMartialArtsDiscipline(
                    MartialArtsDiscipline(name = name.trim(), glyph = glyph.trim(), tagline = tagline.trim()),
                )
                onBack()
            })
        }
    }
}
