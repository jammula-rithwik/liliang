package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.data.ComboItem
import com.liliang.app.data.ComboMove
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton

// The bag-session planning surfaces: pick a combo for a round (combos ONLY —
// forms have no business on the bag), or build a new combo on the spot from
// the same move palette the Library's combo editor uses.

/** Combos-only picker for one round of bag work. */
@Composable
fun ComboPickDialog(
    combos: List<ComboItem>,
    onDismiss: () -> Unit,
    onPick: (ComboItem) -> Unit,
    onCreateNew: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matching = combos.filter {
        query.isBlank() || it.name.contains(query, true) || it.discipline.contains(query, true)
    }
    InkDialog(title = "Combo for this round", onDismiss = onDismiss) {
        if (combos.size > 6) {
            InkTextField(label = "Search", value = query, onValueChange = { query = it })
        }
        // Building something new is a first-class move, not a fallback.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(InkShapes.chip)
                .border(1.dp, InkColors.Gold.copy(alpha = 0.5f), InkShapes.chip)
                .clickable(onClick = onCreateNew)
                .padding(horizontal = InkSpacing.m, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkText("新", style = InkType.Seal.copy(fontSize = 16.sp), color = InkColors.Gold)
            Spacer(Modifier.width(InkSpacing.s))
            InkText("New combo — build it now", style = InkType.BodyStrong, color = InkColors.Bone)
        }
        matching.forEach { combo ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.chip)
                    .border(1.dp, InkColors.Line, InkShapes.chip)
                    .clickable { onPick(combo) }
                    .padding(horizontal = InkSpacing.m, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    InkText(combo.name, style = InkType.BodyStrong)
                    val moves = combo.moves.joinToString(" · ") { it.displayName }
                    if (moves.isNotBlank()) {
                        InkText(moves, style = InkType.Label.copy(letterSpacing = 0.2.sp), color = InkColors.BoneFaint, maxLines = 1)
                    }
                }
                if (combo.discipline.isNotBlank()) {
                    InkText(combo.discipline, style = InkType.Label, color = InkColors.BoneFaint)
                }
            }
        }
        if (matching.isEmpty() && combos.isNotEmpty()) {
            InkText("No combo matches.", style = InkType.BodyDim, color = InkColors.BoneFaint)
        }
        GhostButton("Cancel", onClick = onDismiss)
    }
}

/**
 * Build a combo on the spot from the move-pill palette (the Library editor's
 * palette, reusable here). Returns a throwaway [ComboItem] for today's rounds;
 * "save to library" also files it for future sessions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComboBuilderDialog(
    suggestedDiscipline: String,
    onDismiss: () -> Unit,
    onUse: (combo: ComboItem, saveToLibrary: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var moveIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var save by rememberSaveable { mutableStateOf(false) }
    val moves = moveIds.mapNotNull(ComboMove::fromId)

    // An unnamed combo names itself from its moves — "Jab, Cross, Left hook".
    fun effectiveName(): String =
        name.trim().ifBlank { moves.joinToString(", ") { it.displayName } }

    InkDialog(title = "Build a combo", onDismiss = onDismiss) {
        InkTextField(label = "Name (optional)", value = name, onValueChange = { name = it })

        InkText("SEQUENCE · tap to remove", style = InkType.Label, color = InkColors.BoneFaint)
        if (moves.isEmpty()) {
            InkText("Tap moves below — they land here in order.", style = InkType.BodyDim, color = InkColors.BoneFaint)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s), verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                moves.forEachIndexed { index, move ->
                    Box(
                        modifier = Modifier
                            .clip(InkShapes.chip)
                            .background(InkColors.Vermillion.copy(alpha = 0.16f))
                            .border(1.dp, InkColors.Vermillion.copy(alpha = 0.5f), InkShapes.chip)
                            .clickable { moveIds = moveIds.toMutableList().also { it.removeAt(index) } }
                            .padding(horizontal = InkSpacing.m, vertical = 6.dp),
                    ) {
                        InkText("${index + 1}. ${move.displayName}", style = InkType.BodyDim, color = InkColors.Bone)
                    }
                }
            }
        }

        ComboMove.groupedByCategory().forEach { (category, categoryMoves) ->
            InkText(category.label.uppercase(), style = InkType.Label, color = InkColors.BoneFaint)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s), verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                categoryMoves.forEach { move ->
                    InkChip(
                        text = move.displayName,
                        selected = false,
                        onClick = { moveIds = moveIds + move.id },
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        InkChip(
            text = if (save) "✓ Also save to library" else "Also save to library",
            selected = save,
            onClick = { save = !save },
            tint = InkColors.Gold,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            SealButton(
                "Use for this round",
                hanzi = "拳",
                enabled = moves.isNotEmpty(),
                onClick = {
                    onUse(
                        ComboItem(
                            id = 0L,
                            name = effectiveName(),
                            discipline = suggestedDiscipline,
                            moves = moves,
                        ),
                        save,
                    )
                },
            )
            GhostButton("Cancel", onClick = onDismiss)
        }
    }
}
