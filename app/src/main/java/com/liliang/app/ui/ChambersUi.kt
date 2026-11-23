package com.liliang.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.Overlay
import com.liliang.app.data.Chamber
import com.liliang.app.data.ChamberCatalog
import com.liliang.app.data.ChamberPace
import com.liliang.app.data.ChamberProgress
import com.liliang.app.data.ChamberSeason
import com.liliang.app.data.ChamberState
import com.liliang.app.data.chineseNumeral
import com.liliang.app.design.BrushHanzi
import com.liliang.app.design.GhostButton
import com.liliang.app.design.GoldBurst
import com.liliang.app.design.HanziWatermark
import com.liliang.app.design.InkCard
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.LocalMotionEnabled
import com.liliang.app.design.SealButton
import com.liliang.app.design.SectionKicker
import com.liliang.app.design.Stamp
import com.liliang.app.design.drawInkBlot
import com.liliang.app.design.inkBleedIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// 三十六房 — the 36 Chambers (R21). The Hall's opening section and each
// chamber's own page. Every chamber has a painted door: its pillar's ink wash,
// a brush stroke seeded by its number, and one character in the calligraphy
// face. Locked doors sit in shadow; the current season's doors breathe in
// vermillion; a passed door turns to gold leaf with the 成 seal.

private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM")

private fun Chamber.pillarColor(): Color = Color(pillar.argb)

private fun stateFor(viewModel: LiliangViewModel, chamber: Chamber, today: LocalDate): ChamberState =
    ChamberCatalog.stateOf(chamber, viewModel.chamberPasses.containsKey(chamber.number), today)

/** The Hall's opening section: the title, the current season's three chambers, then the map of all 36. */
fun LazyListScope.chamberHallItems(viewModel: LiliangViewModel) {
    item("chambers-title") { ChambersTitle(viewModel) }
    item("chambers-season") { CurrentSeasonCard(viewModel) }
    item("chambers-map-label") { SectionKicker("图", "THE MAP · 2026 – 2029") }
    ChamberCatalog.years.forEach { year ->
        item("chambers-year-${year.number}") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText(year.hanzi, style = BrushHanzi(26.sp), color = InkColors.Vermillion)
                InkText("YEAR ${year.number} · ${year.title.uppercase()}", style = InkType.Label, color = InkColors.BoneDim)
            }
        }
        ChamberCatalog.seasons.filter { it.year == year.number }.forEach { season ->
            item("chambers-row-${season.code}") { SeasonMapRow(viewModel, season) }
        }
    }
    item("chambers-end") { Spacer(Modifier.height(InkSpacing.s)) }
}

@Composable
private fun ChambersTitle(viewModel: LiliangViewModel) {
    val today = remember { LocalDate.now() }
    val passed = viewModel.chamberPasses.size
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
        Box(Modifier.fillMaxWidth()) {
            HanziWatermark("房", fontSize = 120.dp, modifier = Modifier.align(Alignment.CenterEnd))
            Column {
                InkText("三十六房", style = BrushHanzi(44.sp), color = InkColors.Bone)
                InkText("THE 36 CHAMBERS", style = InkType.Label, color = InkColors.Vermillion)
                Spacer(Modifier.height(InkSpacing.xs))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
                    InkText("$passed", style = InkType.NumberLarge.copy(fontSize = 30.sp), color = if (passed > 0) InkColors.Gold else InkColors.Bone)
                    InkText("of 36 passed", style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
        // Thirty-six notches, one per chamber: gold when passed, vermillion for this season's.
        val current = ChamberCatalog.currentSeason(today)
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * 35) / 36f
            ChamberCatalog.chambers.forEachIndexed { i, chamber ->
                val color = when {
                    viewModel.chamberPasses.containsKey(chamber.number) -> InkColors.Gold
                    chamber.seasonCode == current.code -> InkColors.Vermillion.copy(alpha = 0.8f)
                    else -> InkColors.Bone.copy(alpha = 0.10f)
                }
                drawRect(color, topLeft = Offset(i * (w + gap), 0f), size = Size(w, size.height))
            }
        }
    }
}

@Composable
private fun CurrentSeasonCard(viewModel: LiliangViewModel) {
    val today = remember { LocalDate.now() }
    val season = ChamberCatalog.currentSeason(today)
    val week = season.weekOf(today)
    val status = when {
        today.isBefore(season.start) -> {
            val days = ChronoUnit.DAYS.between(today, season.start)
            "Begins Mon ${season.start.format(dayMonth)} · in $days ${if (days == 1L) "day" else "days"}"
        }
        week >= 13 -> "Gate week · film the trials"
        else -> "Week $week of 12"
    }
    InkCard(modifier = Modifier.fillMaxWidth().inkBleedIn(key = "chambers-season-${season.code}"), accent = InkColors.Vermillion) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
            InkText(season.hanzi, style = BrushHanzi(40.sp), color = InkColors.Vermillion)
            Column(Modifier.weight(1f)) {
                InkText("第${chineseNumeral(season.number)}季 · ${season.code} · ${season.period.uppercase()}", style = InkType.Label, color = InkColors.BoneFaint)
                InkText(season.title, style = InkType.Title)
                InkText("${season.start.format(dayMonthYear)} – ${season.end.format(dayMonthYear)}", style = InkType.Label, color = InkColors.BoneDim)
            }
        }
        Spacer(Modifier.height(InkSpacing.s))
        InkText(status, style = InkType.Body, color = InkColors.Bone)
        Spacer(Modifier.height(InkSpacing.xs))
        TimeBar(fraction = season.timeFraction(today))
        Spacer(Modifier.height(InkSpacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
            ChamberCatalog.chambersIn(season.code).forEach { chamber ->
                CurrentChamberRow(
                    chamber = chamber,
                    state = stateFor(viewModel, chamber, today),
                    progress = viewModel.chamberProgress[chamber.number] ?: ChamberProgress(),
                    season = season,
                    today = today,
                    onClick = { viewModel.pushOverlay(Overlay.Chamber(chamber.number)) },
                )
            }
        }
    }
}

@Composable
private fun CurrentChamberRow(
    chamber: Chamber,
    state: ChamberState,
    progress: ChamberProgress,
    season: ChamberSeason,
    today: LocalDate,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(InkShapes.card).clickable(onClick = onClick).padding(vertical = InkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        ChamberDoor(chamber = chamber, state = state, size = 76.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InkText("第${chineseNumeral(chamber.number)}房 · ${chamber.hanzi}", style = InkType.Label, color = chamber.pillarColor())
            InkText(chamber.title, style = InkType.Heading, maxLines = 1)
            InkText(chamber.feat, style = InkType.Label.copy(fontSize = 11.sp, letterSpacing = 0.2.sp), color = InkColors.BoneDim, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            ChamberProgressLine(chamber, state, progress, season, today)
        }
    }
}

/** The progress bar with the season's clock as a tick across it, and the words under it. */
@Composable
private fun ChamberProgressLine(chamber: Chamber, state: ChamberState, progress: ChamberProgress, season: ChamberSeason, today: LocalDate) {
    if (state == ChamberState.PASSED) {
        InkText("成 · passed", style = InkType.Label, color = InkColors.Gold)
        return
    }
    if (!progress.isMeasured) {
        InkText(
            if (progress.label.isNotBlank()) progress.label else if (chamber.isGated) "⛩ gated · judged on film" else "Judged on film",
            style = InkType.Label,
            color = InkColors.BoneFaint,
        )
        return
    }
    val pace = ChamberCatalog.pace(progress, season, today)
    val fill = if (progress.isReady) InkColors.Gold else chamber.pillarColor()
    val clock = if (today.isBefore(season.start)) null else season.timeFraction(today)
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(InkColors.Bone.copy(alpha = 0.08f), cornerRadius = r)
        drawRoundRect(fill, size = Size(size.width * progress.fraction, size.height), cornerRadius = r)
        clock?.let {
            val x = size.width * it
            drawLine(InkColors.Bone.copy(alpha = 0.85f), Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 2.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth()) {
        InkText(progress.label, style = InkType.Label, color = InkColors.BoneDim, modifier = Modifier.weight(1f))
        pace?.let {
            InkText(
                it.label,
                style = InkType.Label,
                color = when (it) {
                    ChamberPace.READY -> InkColors.Gold
                    ChamberPace.AHEAD -> InkColors.Moss
                    ChamberPace.ON_TRACK -> InkColors.BoneDim
                    ChamberPace.BEHIND -> InkColors.Vermillion
                },
            )
        }
    }
}

@Composable
private fun TimeBar(fraction: Float) {
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        drawRect(InkColors.Bone.copy(alpha = 0.08f))
        drawRect(InkColors.Bone.copy(alpha = 0.45f), size = Size(size.width * fraction, size.height))
    }
}

@Composable
private fun SeasonMapRow(viewModel: LiliangViewModel, season: ChamberSeason) {
    val today = remember { LocalDate.now() }
    val isNow = ChamberCatalog.currentSeason(today).code == season.code
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
        Column(Modifier.width(70.dp).padding(top = 6.dp)) {
            InkText(season.hanzi, style = BrushHanzi(22.sp), color = if (isNow) InkColors.Vermillion else InkColors.BoneDim, maxLines = 1)
            InkText("${season.code} · ${season.period}", style = InkType.Label.copy(fontSize = 9.sp), color = InkColors.BoneFaint)
            InkText(season.start.format(DateTimeFormatter.ofPattern("MMM yyyy")), style = InkType.Label.copy(fontSize = 9.sp), color = InkColors.BoneFaint)
        }
        ChamberCatalog.chambersIn(season.code).forEach { chamber ->
            Column(
                Modifier.weight(1f).clip(InkShapes.card).clickable { viewModel.pushOverlay(Overlay.Chamber(chamber.number)) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChamberDoor(chamber = chamber, state = stateFor(viewModel, chamber, today), size = 64.dp)
                Spacer(Modifier.height(3.dp))
                InkText(chamber.title, style = InkType.Label.copy(fontSize = 9.sp, letterSpacing = 0.2.sp), color = InkColors.BoneDim, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * A chamber's painted door. Each one is its own picture: the pillar's ink wash,
 * two blots seeded by the chamber's number, a lattice frame, and its character.
 */
@Composable
fun ChamberDoor(chamber: Chamber, state: ChamberState, size: Dp, modifier: Modifier = Modifier) {
    val pillar = chamber.pillarColor()
    val dim = state == ChamberState.LOCKED
    val breathing = state == ChamberState.CURRENT && LocalMotionEnabled.current
    val pulse = if (breathing) {
        val transition = rememberInfiniteTransition(label = "door-${chamber.number}")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse),
            label = "door-pulse-${chamber.number}",
        ).value
    } else 0.75f
    val border = when (state) {
        ChamberState.PASSED -> InkColors.Gold.copy(alpha = 0.85f)
        ChamberState.CURRENT -> InkColors.Vermillion.copy(alpha = pulse)
        ChamberState.UPCOMING -> InkColors.Vermillion.copy(alpha = 0.55f)
        ChamberState.OPEN -> InkColors.VermillionDeep.copy(alpha = 0.7f)
        ChamberState.LOCKED -> InkColors.Line
    }
    Box(
        modifier
            .size(size)
            .clip(InkShapes.card)
            .border(if (state == ChamberState.LOCKED) 1.dp else 1.5.dp, border, InkShapes.card),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(InkColors.PaperRaised)
            val wash = if (state == ChamberState.PASSED) InkColors.Gold else pillar
            val c = Offset(this.size.width * 0.5f, this.size.height * 0.56f)
            val radius = this.size.minDimension * 0.78f
            drawCircle(
                brush = Brush.radialGradient(listOf(wash.copy(alpha = if (dim) 0.16f else 0.55f), Color.Transparent), center = c, radius = radius),
                radius = radius,
                center = c,
            )
            drawInkBlot(
                center = Offset(this.size.width * 0.30f, this.size.height * 0.72f),
                radius = this.size.minDimension * 0.22f,
                color = wash,
                seed = chamber.number * 7 + 3,
                alpha = if (dim) 0.07f else 0.24f,
            )
            drawInkBlot(
                center = Offset(this.size.width * 0.74f, this.size.height * 0.28f),
                radius = this.size.minDimension * 0.13f,
                color = InkColors.Bone,
                seed = chamber.number * 13 + 1,
                alpha = 0.05f,
            )
            val inset = this.size.minDimension * 0.08f
            val stroke = 1.dp.toPx()
            drawRect(
                InkColors.Bone.copy(alpha = 0.07f),
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
                style = Stroke(stroke),
            )
            drawLine(InkColors.Bone.copy(alpha = 0.04f), Offset(this.size.width / 2, inset), Offset(this.size.width / 2, this.size.height - inset), stroke)
        }
        InkText(
            chamber.glyph,
            style = BrushHanzi((size.value * 0.48f).sp),
            color = when (state) {
                ChamberState.PASSED -> InkColors.GoldBright
                ChamberState.LOCKED -> InkColors.BoneFaint.copy(alpha = 0.5f)
                else -> InkColors.Bone
            },
        )
        InkText(
            chineseNumeral(chamber.number),
            style = InkType.Seal.copy(fontSize = (size.value * 0.13f).coerceAtLeast(9f).sp),
            color = if (dim) InkColors.BoneFaint.copy(alpha = 0.5f) else InkColors.BoneDim,
            modifier = Modifier.align(Alignment.TopStart).padding(start = size * 0.09f, top = size * 0.06f),
        )
        if (state == ChamberState.PASSED) {
            Stamp("成", tint = InkColors.Gold, filled = true, size = size * 0.3f, modifier = Modifier.align(Alignment.BottomEnd).padding(size * 0.05f))
        }
        if (chamber.isGated && state != ChamberState.PASSED) {
            InkText("⛩", style = InkType.Label.copy(fontSize = (size.value * 0.13f).coerceAtLeast(9f).sp), color = InkColors.BoneFaint, modifier = Modifier.align(Alignment.TopEnd).padding(end = size * 0.07f, top = size * 0.05f))
        }
        if (dim) Box(Modifier.fillMaxSize().background(InkColors.Paper.copy(alpha = 0.40f)))
    }
}

/** A chamber's own page: the door large, the trial, the gate, what to learn from, progress, and the seal. */
@Composable
fun ChamberPage(viewModel: LiliangViewModel, number: Int, onBack: () -> Unit) {
    LaunchedEffect(number) {
        if (viewModel.martialArtsForms.isEmpty()) viewModel.refreshMartialArtsForms()
        viewModel.refreshChambers()
    }
    val chamber = ChamberCatalog.chamber(number)
    val season = chamber?.let { ChamberCatalog.season(it.seasonCode) }
    if (chamber == null || season == null) {
        PlaceholderScreen("空", "Chamber not found", "There are 36.", onBack = onBack)
        return
    }
    val today = remember { LocalDate.now() }
    val passedAt = viewModel.chamberPasses[number]
    val state = ChamberCatalog.stateOf(chamber, passedAt != null, today)
    val progress = viewModel.chamberProgress[number] ?: ChamberProgress()
    val form = chamber.metric.formId.takeIf { it.isNotBlank() }?.let { id -> viewModel.martialArtsForms.firstOrNull { it.id == id } }
    var confirmPass by remember { mutableStateOf(false) }
    var confirmReopen by remember { mutableStateOf(false) }
    var burst by remember { mutableStateOf<Any?>(null) }
    val pillar = chamber.pillarColor()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                GhostButton("‹ Back", onClick = onBack)
                Spacer(Modifier.height(InkSpacing.m))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ChamberDoor(chamber = chamber, state = state, size = 168.dp)
                    GoldBurst(trigger = burst, modifier = Modifier.size(240.dp))
                }
                Spacer(Modifier.height(InkSpacing.m))
                InkText("第${chineseNumeral(chamber.number)}房", style = InkType.Label, color = pillar, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                InkText(chamber.hanzi, style = BrushHanzi(34.sp), color = InkColors.Bone, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                InkText(chamber.title, style = InkType.Title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(InkSpacing.xs))
                InkText(
                    "${chamber.pillar.hanzi} ${chamber.pillar.label} · ${season.code} ${season.hanzi} ${season.title} · ${season.start.format(dayMonth)} – ${season.end.format(dayMonthYear)}",
                    style = InkType.Label,
                    color = InkColors.BoneFaint,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
        item("trial") {
            InkCard(modifier = Modifier.fillMaxWidth(), accent = pillar) {
                SectionKicker("试", "THE TRIAL")
                Spacer(Modifier.height(InkSpacing.xs))
                InkText(chamber.feat, style = InkType.Body)
                if (state != ChamberState.PASSED && progress.isMeasured) {
                    Spacer(Modifier.height(InkSpacing.m))
                    ChamberProgressLine(chamber, state, progress, season, today)
                } else if (state != ChamberState.PASSED && progress.label.isNotBlank()) {
                    Spacer(Modifier.height(InkSpacing.s))
                    InkText(progress.label, style = InkType.Label, color = InkColors.BoneFaint)
                }
            }
        }
        if (chamber.isGated) {
            item("gate") {
                InkCard(modifier = Modifier.fillMaxWidth()) {
                    SectionKicker("⛩", "THE GATE")
                    Spacer(Modifier.height(InkSpacing.xs))
                    InkText(chamber.gate, style = InkType.Body, color = InkColors.BoneDim)
                }
            }
        }
        if (chamber.learnFrom.isNotBlank()) {
            item("learn") {
                InkCard(modifier = Modifier.fillMaxWidth()) {
                    SectionKicker("学", "LEARN FROM")
                    Spacer(Modifier.height(InkSpacing.xs))
                    InkText(chamber.learnFrom, style = InkType.Body, color = InkColors.BoneDim)
                }
            }
        }
        item("seal") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (passedAt != null) {
                    Stamp("成", tint = InkColors.Gold, filled = true, size = 56.dp)
                    Spacer(Modifier.height(InkSpacing.s))
                    InkText(
                        "Passed ${runCatching { LocalDate.parse(passedAt).format(dayMonthYear) }.getOrDefault(passedAt)}",
                        style = InkType.Body,
                        color = InkColors.Gold,
                    )
                    Spacer(Modifier.height(InkSpacing.s))
                    GhostButton("Reopen", onClick = { confirmReopen = true })
                } else {
                    SealButton("Seal it · passed", hanzi = "成", tint = InkColors.Gold, onClick = { confirmPass = true })
                    Spacer(Modifier.height(InkSpacing.xs))
                    InkText("Film the trial first. Then it's yours.", style = InkType.Label, color = InkColors.BoneFaint)
                }
            }
        }
        if (form != null && form.moves.isNotEmpty()) {
            item("moves-label") { SectionKicker("式", "${form.displayShortTitle.uppercase()} · ${form.moveCount} MOVES") }
            val reached = progress.value.toInt()
            items(form.moves.size) { i ->
                val move = form.moves[i]
                val done = move.index <= reached
                val next = move.index == reached + 1
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    InkText(
                        "%02d".format(move.index),
                        style = InkType.NumberSmall.copy(fontSize = 13.sp),
                        color = when {
                            done -> pillar
                            next -> InkColors.Vermillion
                            else -> InkColors.BoneFaint
                        },
                    )
                    InkText(
                        move.name,
                        style = InkType.Body,
                        color = when {
                            done -> InkColors.Bone
                            next -> InkColors.Vermillion
                            else -> InkColors.BoneFaint
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (move.section.isNotBlank()) InkText(move.section, style = InkType.Label, color = InkColors.BoneFaint)
                }
            }
        }
    }

    if (confirmPass) {
        ConfirmationDialog(
            title = "Seal ${chamber.title}?",
            message = "You've passed the trial on film. 第${chineseNumeral(chamber.number)}房 becomes yours.",
            confirmLabel = "Seal it",
            onDismiss = { confirmPass = false },
            onConfirm = {
                viewModel.setChamberPassed(number, true)
                burst = System.currentTimeMillis()
                confirmPass = false
            },
        )
    }
    if (confirmReopen) {
        ConfirmationDialog(
            title = "Reopen ${chamber.title}?",
            message = "Takes the seal off. Nothing else changes.",
            confirmLabel = "Reopen",
            onDismiss = { confirmReopen = false },
            onConfirm = {
                viewModel.setChamberPassed(number, false)
                confirmReopen = false
            },
        )
    }
}
