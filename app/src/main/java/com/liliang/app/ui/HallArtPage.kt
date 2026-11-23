package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.Overlay
import com.liliang.app.data.ComboItem
import com.liliang.app.data.MartialArtsDiscipline
import com.liliang.app.data.MartialArtsForm
import com.liliang.app.data.MartialArtsFormProgress
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.GoldBurst
import com.liliang.app.design.HanziWatermark
import com.liliang.app.design.InkArc
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton
import com.liliang.app.design.BrushHanzi
import com.liliang.app.design.Stamp
import com.liliang.app.design.goldShimmerOnce
import com.liliang.app.design.rememberRevealLedger

/**
 * One art's page: a colossal glyph, training stats, the discipline's forms
 * (gold-leaf when mastered, arcs when in progress), and its combos — tap a
 * combo to drill it on the round timer.
 */
@Composable
fun HallArtPage(viewModel: LiliangViewModel, discipline: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.refreshMartialArtsForms() }
    val hall = viewModel.martialArtsHall
    val progress = viewModel.martialArtsFormProgress
    val ledger = rememberRevealLedger()
    val info = hall.disciplines.firstOrNull { it.name == discipline }
        ?: MartialArtsDiscipline(discipline, "", "")
    val stats = hall.disciplineStats.firstOrNull { it.name == discipline }
    val forms = hall.forms.filter { it.discipline.equals(discipline, ignoreCase = true) }
    val combos = viewModel.configSnapshot.combos.filter { it.discipline.equals(discipline, ignoreCase = true) }

    // Drilling from the Hall runs the real timer overlay and LOGS the rounds
    // as a martial-arts session for this discipline — training counts.
    fun drill(rounds: List<ComboItem>) {
        viewModel.startRoundTimer(
            com.liliang.app.RoundTimerSetup(
                rounds = rounds,
                workSeconds = 180,
                restSeconds = 60,
                callMoves = false,
                origin = com.liliang.app.RoundTimerOrigin.Hall(discipline),
            ),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
    ) {
        item("hero") {
            Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    InkText(info.glyph, style = BrushHanzi(120.sp), color = InkColors.Bone.copy(alpha = 0.08f), maxLines = 1)
                }
                Column {
                    GhostButton("‹ Back", onClick = onBack)
                    Spacer(Modifier.height(InkSpacing.m))
                    InkText(info.glyph, style = BrushHanzi(56.sp), color = InkColors.Bone, maxLines = 1)
                    InkText(discipline, style = InkType.Masthead.copy(fontSize = 28.sp))
                    if (info.tagline.isNotBlank()) {
                        InkText(info.tagline, style = InkType.Body, color = InkColors.BoneDim)
                    }
                }
            }
        }

        if (stats != null) {
            item("stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.xl)) {
                    StatBlock("${stats.thisYearSessions}", "THIS YEAR")
                    StatBlock("${stats.totalHours.toInt()}h", "TOTAL")
                    if (stats.comboCount > 0) StatBlock("${stats.comboCount}", "COMBOS")
                }
            }
        }

        item("forms-label") {
            Column {
                BrushDivider(seed = discipline.hashCode())
                Spacer(Modifier.height(InkSpacing.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.liliang.app.design.SectionKicker("套路", "FORMS", modifier = Modifier.weight(1f))
                    GhostButton("+ New form", onClick = {
                        viewModel.loadFormEditorFor(null, discipline)
                        viewModel.pushOverlay(com.liliang.app.Overlay.FormEditor(formId = null, discipline = discipline))
                    })
                }
            }
        }
        if (forms.isNotEmpty()) {
            items(forms.size) { i ->
                FormRow(
                    form = forms[i],
                    progress = progress[forms[i].id],
                    ledger = ledger,
                    onOpenTracker = { viewModel.pushOverlay(Overlay.FormTracker(forms[i].id)) },
                    onMarkMastered = { viewModel.setFormMastered(forms[i].id, true) },
                    onReopen = { viewModel.setFormMastered(forms[i].id, false) },
                )
            }
        }

        if (combos.isNotEmpty()) {
            item("combos-label") {
                Column {
                    BrushDivider(seed = discipline.hashCode() + 1)
                    Spacer(Modifier.height(InkSpacing.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InkText("COMBOS", style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.weight(1f))
                        GhostButton("Train all", hanzi = "武", onClick = { drill(combos) })
                    }
                }
            }
            items(combos.size) { i ->
                // One combo drilled = three rounds of it.
                ComboRow(combo = combos[i], onPlay = { drill(List(3) { combos[i] }) })
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column {
        InkText(value, style = InkType.NumberSmall.copy(fontSize = 22.sp))
        InkText(label, style = InkType.LabelSmall, color = InkColors.BoneFaint)
    }
}

@Composable
private fun FormRow(
    form: MartialArtsForm,
    progress: MartialArtsFormProgress?,
    ledger: com.liliang.app.design.RevealLedger,
    onOpenTracker: () -> Unit,
    onMarkMastered: () -> Unit,
    onReopen: () -> Unit,
) {
    val mastered = form.isMastered || (form.moveCount > 0 && (progress?.learnedCount ?: 0) >= form.moveCount)
    val pct = if (form.moveCount == 0) (if (mastered) 1f else 0f) else (progress?.percent ?: 0f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.card)
            .background(if (mastered) InkColors.Gold.copy(alpha = 0.05f) else InkColors.PaperRaised)
            .border(1.dp, if (mastered) InkColors.Gold.copy(alpha = 0.4f) else InkColors.Line, InkShapes.card)
            .pointerInput(form.id, mastered) {
                detectTapGestures(
                    // A mastered form still opens — you can review its moves any time.
                    onTap = { if (form.moveCount > 0) onOpenTracker() },
                    onLongPress = { if (mastered) onReopen() },
                )
            }
            .padding(InkSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        if (form.moveCount > 0) {
            InkArc(progress = pct, key = "fr:${form.id}:${(pct * 100).toInt()}", size = 52.dp, thickness = 5.dp) {
                if (mastered) InkText("成", style = InkType.Seal.copy(fontSize = 16.sp), color = InkColors.Gold)
                else InkText("${(pct * 100).toInt()}", style = InkType.NumberSmall.copy(fontSize = 13.sp))
            }
        }
        Column(Modifier.weight(1f)) {
            InkText(form.displayShortTitle, style = InkType.Heading)
            InkText(form.subtitle.ifBlank { form.title }, style = InkType.Label.copy(letterSpacing = 0.2.sp), color = InkColors.BoneFaint, maxLines = 1)
            if (form.moveCount > 0 && !mastered) {
                InkText("${progress?.learnedCount ?: 0} / ${form.moveCount} learned — tap to track", style = InkType.Label, color = InkColors.Vermillion)
            }
        }
        Box(contentAlignment = Alignment.Center) {
            when {
                mastered -> Stamp("已成", tint = InkColors.Gold, filled = true, size = 44.dp, modifier = Modifier.goldShimmerOnce(key = "artmastered:${form.id}", ledger = ledger))
                form.moveCount == 0 -> GhostButton("Mastered", onClick = onMarkMastered, tint = InkColors.Gold)
            }
        }
    }
}

@Composable
private fun ComboRow(combo: ComboItem, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.chip)
            .border(1.dp, InkColors.Line, InkShapes.chip)
            .clickable(onClick = onPlay)
            .padding(InkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            InkText(combo.name, style = InkType.BodyStrong, maxLines = 1)
            if (combo.moves.isNotEmpty()) {
                InkText(combo.moves.joinToString(" · ") { it.displayName }, style = InkType.Label, color = InkColors.BoneFaint, maxLines = 1)
            }
        }
        Stamp("武", tint = InkColors.Vermillion, size = 30.dp)
    }
}

/**
 * The move-by-move learning tracker. Tap a move to mark it learned; the arc
 * fills, and completing the last move triggers the mastery burst. Whole-form
 * forms (no move list) just get the mastered seal.
 */
@Composable
fun FormTrackerScreen(viewModel: LiliangViewModel, formId: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { if (viewModel.martialArtsForms.isEmpty()) viewModel.refreshMartialArtsForms() }
    val form = viewModel.martialArtsForms.firstOrNull { it.id == formId }
    val progress = viewModel.martialArtsFormProgress[formId]
    val ledger = rememberRevealLedger()
    if (form == null) {
        PlaceholderScreen("空", "Form not found", "It may have been deleted.", onBack = onBack)
        return
    }
    val learned = progress?.learnedCount ?: 0
    val pct = if (form.moveCount == 0) 0f else learned.toFloat() / form.moveCount
    val complete = form.moveCount > 0 && learned >= form.moveCount
    val sections = remember(form) { form.moves.groupBy { it.section } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        item("top") {
            Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                Column {
                    GhostButton("‹ Back", onClick = onBack)
                    Spacer(Modifier.height(InkSpacing.m))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
                        Box(contentAlignment = Alignment.Center) {
                            InkArc(progress = pct, key = "tracker:$formId:$learned", size = 80.dp) {
                                InkText("${(pct * 100).toInt()}%", style = InkType.NumberSmall, color = if (complete) InkColors.Gold else InkColors.Bone)
                            }
                            if (complete) {
                                GoldBurst(trigger = if (ledger.shouldFire("trackerburst:$formId")) learned else null, modifier = Modifier.size(140.dp))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            InkText(form.displayShortTitle, style = InkType.Title)
                            InkText(form.title, style = BrushHanzi(16.sp), color = InkColors.BoneDim, maxLines = 2)
                            InkText("$learned / ${form.moveCount} learned", style = InkType.Label, color = InkColors.Vermillion)
                        }
                    }
                    Spacer(Modifier.height(InkSpacing.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                        SealButton(
                            if (form.isMastered) "Reopen" else "Mark mastered",
                            hanzi = "成",
                            compact = true,
                            tint = if (form.isMastered) InkColors.InkWash else InkColors.Gold,
                            onClick = { viewModel.setFormMastered(formId, !form.isMastered) },
                        )
                        GhostButton("Edit form", onClick = {
                            viewModel.loadFormEditorFor(formId, form.discipline)
                            viewModel.pushOverlay(com.liliang.app.Overlay.FormEditor(formId = formId, discipline = form.discipline))
                        })
                    }
                    Spacer(Modifier.height(InkSpacing.s))
                    BrushDivider(seed = formId.hashCode())
                }
            }
        }

        sections.forEach { (section, moves) ->
            if (section.isNotBlank()) {
                item("sec:$section") {
                    Spacer(Modifier.height(InkSpacing.s))
                    InkText(section, style = BrushHanzi(18.sp), color = InkColors.Vermillion)
                }
            }
            items(moves.size, key = { moves[it].index }) { idx ->
                val move = moves[idx]
                val isLearned = progress?.isLearned(move.index) ?: false
                MoveRow(
                    index = move.index,
                    name = move.name,
                    learned = isLearned,
                    onToggle = { viewModel.toggleMartialArtsMoveLearned(formId, move.index) },
                )
            }
        }
    }
}

@Composable
private fun MoveRow(index: Int, name: String, learned: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.chip)
            .clickable(onClick = onToggle)
            .padding(horizontal = InkSpacing.s, vertical = InkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        // A seal that fills vermillion when the move is learned.
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(InkShapes.seal)
                .background(if (learned) InkColors.Vermillion else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, if (learned) InkColors.Vermillion else InkColors.Line, InkShapes.seal),
            contentAlignment = Alignment.Center,
        ) {
            InkText("$index", style = InkType.LabelSmall, color = if (learned) InkColors.Bone else InkColors.BoneFaint)
        }
        InkText(
            name,
            style = BrushHanzi(16.sp),
            color = if (learned) InkColors.Bone else InkColors.BoneDim,
            modifier = Modifier.weight(1f),
        )
    }
}
