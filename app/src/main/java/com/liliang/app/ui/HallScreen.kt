package com.liliang.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.Overlay
import com.liliang.app.data.DisciplineStats
import com.liliang.app.data.MartialArtsDiscipline
import com.liliang.app.data.MartialArtsForm
import com.liliang.app.data.MartialArtsFormProgress
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.HanziWatermark
import com.liliang.app.design.InkArc
import com.liliang.app.design.InkCard
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.LocalMotionEnabled
import com.liliang.app.design.BrushHanzi
import com.liliang.app.design.Stamp
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.rememberRevealLedger

private data class HallQuote(val text: String, val attribution: String)

private val HallQuotes = listOf(
    HallQuote("I fear not the man who has practiced 10,000 kicks once, but I fear the man who has practiced one kick 10,000 times.", "Bruce Lee"),
    HallQuote("Be like water, my friend.", "Bruce Lee"),
    HallQuote("千里之行，始於足下 — A journey of a thousand miles begins beneath one's feet.", "Laozi"),
    HallQuote("It does not matter how slowly you go as long as you do not stop.", "Confucius"),
    HallQuote("The more you sweat in training, the less you bleed in battle.", "Warrior proverb"),
    HallQuote("拳不離手 — The fist never leaves the hand.", "Chinese proverb"),
    HallQuote("A black belt is a white belt who never quit.", "Dojo proverb"),
    HallQuote("知己知彼，百戰不殆 — Know yourself and know your enemy.", "Sun Tzu"),
    HallQuote("The best fighter is never angry.", "Laozi"),
    HallQuote("四兩撥千斤 — Four ounces deflect a thousand pounds.", "Tai Chi classics"),
)

/**
 * 馆 — the Hall. A temple, not a forge: the 武 watermark behind a master's
 * quote, the forms you're currently learning as dry-brush arcs, and the arts
 * themselves as cards that open their own pages.
 */
@Composable
fun HallScreen(viewModel: LiliangViewModel) {
    LaunchedEffect(Unit) {
        viewModel.refreshMartialArtsForms()
        viewModel.refreshChambers()
    }
    val hall = viewModel.martialArtsHall
    val progress = viewModel.martialArtsFormProgress
    val ledger = rememberRevealLedger()
    // A typo'd art used to be permanent — long-press a card to remove it.
    val pendingDeleteArt = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val learning = remember(hall.forms, progress) {
        hall.forms.filter { form ->
            !form.isMastered && form.moveCount > 0 &&
                (progress[form.id]?.learnedCount ?: 0) in 1 until form.moveCount
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = dockClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.xl),
    ) {
        item("hero") { HallHero() }

        chamberHallItems(viewModel)

        item("seals") { SealAlbumCard(viewModel) }

        item("pulse") { MartialPulse(hall) }

        if (learning.isNotEmpty()) {
            item("learning-label") {
                com.liliang.app.design.SectionKicker("学", "LEARNING")
            }
            item("learning") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                    items(learning.size) { i ->
                        val form = learning[i]
                        LearningTile(
                            form = form,
                            progress = progress[form.id],
                            onClick = { viewModel.pushOverlay(Overlay.FormTracker(form.id)) },
                        )
                    }
                }
            }
        }

        item("arts-label") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                com.liliang.app.design.SectionKicker("门", "THE ARTS", modifier = Modifier.weight(1f))
                com.liliang.app.design.GhostButton("+ Art", onClick = {
                    viewModel.newDisciplineEditor()
                    viewModel.pushOverlay(Overlay.DisciplineEditor)
                })
                com.liliang.app.design.GhostButton("", hanzi = "库", onClick = { viewModel.pushOverlay(Overlay.Library) })
            }
        }
        items(hall.disciplines.chunked(2).size) { rowIndex ->
            val pair = hall.disciplines.chunked(2)[rowIndex]
            Row(
                horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                pair.forEach { discipline ->
                    ArtCard(
                        discipline = discipline,
                        stats = hall.disciplineStats.firstOrNull { it.name == discipline.name },
                        modifier = Modifier.weight(1f).fillMaxHeight().inkBleedIn(key = "art:${discipline.name}", ledger = ledger),
                        onClick = { viewModel.pushOverlay(Overlay.ArtPage(discipline.name)) },
                        onLongPress = { pendingDeleteArt.value = discipline.name },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    pendingDeleteArt.value?.let { name ->
        ConfirmationDialog(
            title = "Remove $name?",
            message = "Takes this art's card off the Hall. Training history is untouched. (Built-in arts return on a fresh install only.)",
            confirmLabel = "Remove",
            onDismiss = { pendingDeleteArt.value = null },
            onConfirm = {
                viewModel.deleteDiscipline(name)
                pendingDeleteArt.value = null
            },
        )
    }
}

@Composable
private fun HallHero() {
    var quoteIndex by remember {
        mutableIntStateOf((java.time.LocalDate.now().toEpochDay() % HallQuotes.size).toInt())
    }
    // The wipe is a TAP response, not an entrance — scroll-backs re-enter this
    // composable and used to replay it every time.
    var wipeRequested by remember { mutableStateOf(false) }
    val quote = HallQuotes[quoteIndex % HallQuotes.size]
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .clip(InkShapes.card)
            .clickable { wipeRequested = true; quoteIndex = (quoteIndex + 1) % HallQuotes.size }
            .padding(vertical = InkSpacing.l),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            HanziWatermark("武", fontSize = 160.dp)
        }
        Column {
            InkText("馆", style = InkType.SealHero.copy(fontSize = 64.sp), color = InkColors.Bone)
            InkText("THE HALL", style = InkType.Label, color = InkColors.BoneFaint)
            Spacer(Modifier.height(InkSpacing.l))
            // A vertical brush-wipe: tapping the hero repaints the new quote
            // top-to-bottom behind a travelling vermillion ink head.
            key(quoteIndex) {
                val motion = LocalMotionEnabled.current
                val wipe = remember { Animatable(if (wipeRequested && motion) 0f else 1f) }
                LaunchedEffect(Unit) {
                    if (wipeRequested && motion) {
                        wipeRequested = false
                        wipe.animateTo(1f, tween(560, easing = LinearEasing))
                    }
                }
                Column(
                    Modifier.drawWithContent {
                        val headY = size.height * wipe.value
                        clipRect(top = 0f, bottom = headY.coerceAtLeast(0.01f)) { this@drawWithContent.drawContent() }
                        if (wipe.value in 0.001f..0.999f) {
                            drawRect(
                                color = InkColors.Vermillion.copy(alpha = 0.85f),
                                topLeft = Offset(0f, headY - 3.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()),
                            )
                        }
                    },
                ) {
                    InkText("「${quote.text}」", style = InkType.Title.copy(fontSize = 19.sp), color = InkColors.Bone, simplify = false)
                    Spacer(Modifier.height(InkSpacing.s))
                    InkText("— ${quote.attribution}", style = InkType.Label, color = InkColors.BoneDim)
                }
            }
        }
    }
}

/**
 * The hall's pulse: a slowly turning ink taiji beside the practice totals,
 * with a wash bar per discipline. The taiji is the Hall's one living visual
 * — it turns only while system motion is on.
 */
@Composable
private fun MartialPulse(hall: com.liliang.app.data.MartialArtsHallSnapshot) {
    val stats = hall.disciplineStats
    val yearSessions = stats.sumOf { it.thisYearSessions }
    val totalHours = stats.sumOf { it.totalHours }
    val artsActive = stats.count { it.totalHours > 0 || it.thisYearSessions > 0 }
    // ONE series — this year's hours. The old silent fallback plotted a rested
    // art's LIFETIME hours on the same axis as everyone else's 8-month figure.
    val bars = remember(stats) {
        stats.filter { it.thisYearHours > 0 || it.totalHours > 0 }
            .sortedByDescending { it.thisYearHours }
            .take(5)
    }
    val maxHours = (bars.maxOfOrNull { it.thisYearHours } ?: 1.0).coerceAtLeast(1.0)

    InkCard(accent = InkColors.Vermillion) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
            TaijiMark(modifier = Modifier.size(76.dp))
            Column(Modifier.weight(1f)) {
                com.liliang.app.design.SectionKicker("武", "THE FLOOR", tint = InkColors.Vermillion)
                Spacer(Modifier.height(InkSpacing.s))
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
                    PulseStat(yearSessions.toString(), "SESSIONS")
                    PulseStat("${totalHours.toInt()}h", "TOTAL")
                    PulseStat(artsActive.toString(), "ARTS")
                }
            }
        }
        if (bars.isNotEmpty()) {
            Spacer(Modifier.height(InkSpacing.m))
            BrushDivider(seed = 909)
            Spacer(Modifier.height(InkSpacing.s))
            bars.forEach { s ->
                val hours = s.thisYearHours
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    InkText(s.name, style = InkType.BodyDim, maxLines = 1, modifier = Modifier.width(96.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(InkShapes.chip)
                            .background(InkColors.Bone.copy(alpha = 0.06f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((hours / maxHours).toFloat().coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .clip(InkShapes.chip)
                                .background(InkColors.Vermillion.copy(alpha = 0.5f)),
                        )
                    }
                    Spacer(Modifier.width(InkSpacing.s))
                    InkText("${hours.toInt()}h", style = InkType.Label, color = InkColors.BoneFaint)
                }
            }
        } else {
            Spacer(Modifier.height(InkSpacing.s))
            InkText("Log a session and the floor comes alive.", style = InkType.BodyDim, color = InkColors.BoneFaint)
        }
    }
}

@Composable
private fun PulseStat(value: String, label: String) {
    Column {
        InkText(value, style = InkType.NumberSmall.copy(fontSize = 22.sp))
        InkText(label, style = InkType.LabelSmall, color = InkColors.BoneFaint)
    }
}

/** A taiji drawn in ink — bone and vermillion — turning slowly while motion is on. */
@Composable
private fun TaijiMark(modifier: Modifier = Modifier) {
    val motion = LocalMotionEnabled.current
    val angle = if (motion) {
        val t = rememberInfiniteTransition(label = "taiji")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Restart),
            label = "spin",
        ).value
    } else 0f
    Canvas(modifier) {
        val r = size.minDimension / 2f - 1.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        rotate(angle, pivot = c) {
            drawCircle(InkColors.Bone, radius = r, center = c)
            // right half vermillion
            drawArc(
                color = InkColors.Vermillion,
                startAngle = -90f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(c.x - r, c.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            )
            // the two swirls
            drawCircle(InkColors.Vermillion, radius = r / 2f, center = Offset(c.x, c.y - r / 2f))
            drawCircle(InkColors.Bone, radius = r / 2f, center = Offset(c.x, c.y + r / 2f))
            // the eyes
            drawCircle(InkColors.Bone, radius = r / 6f, center = Offset(c.x, c.y - r / 2f))
            drawCircle(InkColors.Vermillion, radius = r / 6f, center = Offset(c.x, c.y + r / 2f))
            // a hairline rim
            drawCircle(InkColors.Line, radius = r, center = c, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun LearningTile(form: MartialArtsForm, progress: MartialArtsFormProgress?, onClick: () -> Unit) {
    val pct = progress?.percent ?: 0f
    val current = progress?.currentlyLearningIndex(form)?.let { idx -> form.moves.firstOrNull { it.index == idx } }
    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(InkShapes.card)
            .background(InkColors.PaperRaised)
            .border(1.dp, InkColors.Line, InkShapes.card)
            .clickable(onClick = onClick)
            .padding(InkSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        InkArc(progress = pct, key = "learn:${form.id}:${(pct * 100).toInt()}", size = 64.dp) {
            InkText("${(pct * 100).toInt()}%", style = InkType.NumberSmall.copy(fontSize = 13.sp))
        }
        InkText(form.displayShortTitle, style = InkType.Heading.copy(fontSize = 15.sp), maxLines = 1)
        InkText(
            current?.let { "next · ${it.name}" } ?: "${progress?.learnedCount ?: 0}/${form.moveCount}",
            style = InkType.Label.copy(letterSpacing = 0.3.sp),
            color = InkColors.BoneFaint,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ArtCard(
    discipline: MartialArtsDiscipline,
    stats: DisciplineStats?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
) {
    val view = androidx.compose.ui.platform.LocalView.current
    Box(
        modifier = modifier
            .clip(InkShapes.card)
            .background(InkColors.PaperRaised)
            .border(1.dp, InkColors.Line, InkShapes.card)
            .pointerInput(discipline.name) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = {
                        com.liliang.app.platform.Haptics.confirm(view)
                        onLongPress()
                    },
                )
            },
    ) {
        // A giant faint glyph watermark sunk into the card, bled off the corner.
        InkText(
            discipline.glyph.take(1),
            style = BrushHanzi(120.sp),
            color = InkColors.Bone.copy(alpha = 0.05f),
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 24.dp),
        )
        Column(
            modifier = Modifier.padding(InkSpacing.l),
            verticalArrangement = Arrangement.spacedBy(InkSpacing.xs),
        ) {
            InkText(discipline.glyph, style = BrushHanzi(36.sp), color = InkColors.Bone, maxLines = 1)
            InkText(discipline.name, style = InkType.Heading.copy(fontSize = 15.sp), maxLines = 1)
            InkText(discipline.tagline, style = InkType.Label.copy(letterSpacing = 0.2.sp), color = InkColors.BoneFaint, maxLines = 2)
            if (stats != null && (stats.thisYearSessions > 0 || stats.totalHours > 0)) {
                Spacer(Modifier.height(InkSpacing.xs))
                InkText(
                    buildString {
                        if (stats.thisYearSessions > 0) append("${stats.thisYearSessions} this year")
                        if (stats.totalHours > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${stats.totalHours.toInt()}h total")
                        }
                    },
                    style = InkType.LabelSmall,
                    color = InkColors.Vermillion,
                )
            }
        }
    }
}
