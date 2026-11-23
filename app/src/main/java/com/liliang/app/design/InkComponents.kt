package com.liliang.app.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.platform.Haptics
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// The core ink vocabulary: seals, cards, brush strokes, stamps, numerals,
// tallies, arcs, and the gold burst. Screens compose these; they should
// rarely need raw M3 widgets beyond text fields and dialogs.

/** Material's Text, re-exported defaults aside, to keep imports tidy. */
@Composable
fun InkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = InkType.Body,
    color: Color = InkColors.Bone,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    simplify: Boolean = true,
) {
    androidx.compose.material3.Text(
        // Every Chinese character renders in the one brush hand: traditional
        // glyphs (incl. from imported data) are mapped to simplified, which is
        // all Ma Shan Zheng carries. No-op for Latin text. Curated classics
        // (the Hall's quotes) opt out — 始於足下 stays 始於足下 by decision.
        text = if (simplify) simplifyHanzi(text) else text,
        modifier = modifier,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

// ───────────────────────── Seal button ─────────────────────────

/**
 * The app's primary action — a vermillion seal pressed into the page.
 * Scales under the finger with a confirm haptic; state change is applied
 * on click, the spring is decoration.
 */
@Composable
fun SealButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    tint: Color = InkColors.Vermillion,
    hanzi: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.93f else 1f,
        animationSpec = LiliangSprings.press<Float>().orSnap(),
        label = "sealPress",
    )
    val view = LocalView.current
    val background = when {
        !enabled -> InkColors.PaperHigh
        pressed -> InkColors.VermillionDeep
        else -> tint
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(InkShapes.seal)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                Haptics.confirm(view)
                onClick()
            }
            .padding(
                horizontal = if (compact) InkSpacing.m else InkSpacing.xl,
                vertical = if (compact) 7.dp else InkSpacing.m,
            ),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hanzi != null) {
            InkText(
                hanzi,
                style = InkType.Seal.copy(fontSize = if (compact) 16.sp else 20.sp),
                color = if (enabled) InkColors.Bone else InkColors.BoneFaint,
            )
        }
        if (text.isNotBlank()) {
            InkText(
                text,
                style = if (compact) InkType.BodyDim else InkType.Heading,
                color = if (enabled) InkColors.Bone else InkColors.BoneFaint,
            )
        }
    }
}

/** Quiet secondary action — bone outline, no fill until pressed. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = InkColors.BoneDim,
    hanzi: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    Row(
        modifier = modifier
            .clip(InkShapes.chip)
            .border(1.dp, if (pressed) InkColors.Bone else InkColors.Line, InkShapes.chip)
            .background(if (pressed) InkColors.PaperHigh else Color.Transparent)
            .clickable(interaction, indication = null, enabled = enabled) {
                Haptics.tick(view)
                onClick()
            }
            .padding(horizontal = InkSpacing.m, vertical = 7.dp)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Hanzi rides the brush hand, never the sans face.
        if (hanzi != null) {
            InkText(hanzi, style = InkType.Seal.copy(fontSize = 14.sp), color = tint)
        }
        if (text.isNotBlank()) {
            InkText(text, style = InkType.BodyDim, color = tint)
        }
    }
}

// ───────────────────────── Cards & strokes ─────────────────────────

/** Raised paper panel with a hairline edge. */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    contentPadding: Dp = InkSpacing.l,
    /** Gold key-fret (回纹) brackets in the corners — for featured panels. */
    ornate: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .clip(InkShapes.card)
        .background(InkColors.PaperRaised)
        .border(1.dp, InkColors.Line, InkShapes.card)
        .then(if (ornate) Modifier.fretCorners(alpha = 0.35f, armDp = 12f, insetDp = 5f) else Modifier)
    val view = LocalView.current
    val clickable = if (onClick != null) {
        base.clickable { Haptics.tick(view); onClick() }
    } else {
        base
    }
    val withAccent = if (accent != null) {
        clickable.drawBehind {
            // a brushed left edge, heavier at the top like a loaded stroke
            val w = 3.dp.toPx()
            drawLine(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to accent,
                    1f to accent.copy(alpha = 0.25f),
                ),
                start = Offset(w / 2, 6.dp.toPx()),
                end = Offset(w / 2, size.height - 6.dp.toPx()),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
    } else {
        clickable
    }
    Column(withAccent.padding(contentPadding), content = content)
}

/**
 * A single dry-brush stroke used as a divider — tapered ends, uneven body,
 * deterministic per [seed] so it never dances on recomposition.
 */
@Composable
fun BrushDivider(
    modifier: Modifier = Modifier,
    seed: Int = 7,
    color: Color = InkColors.BoneFaint.copy(alpha = 0.5f),
) {
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .padding(vertical = 0.dp),
    ) {
        val rng = Random(seed)
        val n = 26
        val cy = size.height / 2
        val top = Path()
        val bottom = ArrayList<Offset>(n)
        // A dry patch where the brush ran out for a moment — one per stroke,
        // never near the ends (the loaded start and the lifted finish).
        val dryAt = 0.25f + rng.nextFloat() * 0.5f
        val dryWidth = 0.03f + rng.nextFloat() * 0.05f
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val x = t * size.width
            // taper at both ends; small seeded wobble through the body
            val taper = (sin(t * Math.PI).toFloat()).coerceIn(0.06f, 1f)
            val wobble = 0.55f + rng.nextFloat() * 0.65f
            val dry = if (kotlin.math.abs(t - dryAt) < dryWidth) 0.30f else 1f
            val half = (size.height * 0.42f) * taper * wobble * dry
            if (i == 0) top.moveTo(x, cy - half) else top.lineTo(x, cy - half)
            bottom.add(Offset(x, cy + half))
        }
        for (i in bottom.indices.reversed()) {
            top.lineTo(bottom[i].x, bottom[i].y)
        }
        top.close()
        drawPath(top, color)
        // Bristle streaks trailing off the stroke's tail.
        repeat(3) { s ->
            val y = cy + (rng.nextFloat() - 0.5f) * size.height * 0.6f
            val startX = size.width * (0.86f + rng.nextFloat() * 0.05f)
            drawLine(
                color = color.copy(alpha = color.alpha * 0.5f),
                start = Offset(startX, y),
                end = Offset(startX + size.width * (0.03f + rng.nextFloat() * 0.05f), y),
                strokeWidth = (0.6f + rng.nextFloat() * 0.5f).dp.toPx(),
            )
        }
    }
}

/**
 * Faint long-grain strokes over a wood surface. Order after `.background(
 * InkWood.…)` so the grain sits on the wood, under the content. Static and
 * seeded — the same board always shows the same grain.
 */
fun Modifier.woodGrain(seed: Int, alpha: Float = 0.055f): Modifier = drawBehind {
    val rng = Random(seed)
    val lines = 6
    repeat(lines) { i ->
        val baseY = size.height * (0.14f + 0.72f * i / (lines - 1f)) + (rng.nextFloat() - 0.5f) * 4f
        val dark = rng.nextFloat() < 0.6f
        val grain = Path()
        grain.moveTo(0f, baseY)
        var x = 0f
        val stepW = size.width / 4f
        while (x < size.width) {
            val next = (x + stepW).coerceAtMost(size.width)
            grain.quadraticBezierTo(
                x + stepW / 2f,
                baseY + (rng.nextFloat() - 0.5f) * 5f,
                next,
                baseY + (rng.nextFloat() - 0.5f) * 2.5f,
            )
            x = next
        }
        drawPath(
            grain,
            color = if (dark) Color.Black else Color(0xFF8A6A3F),
            alpha = alpha * (0.6f + rng.nextFloat() * 0.8f),
            style = Stroke(width = (0.7f + rng.nextFloat() * 0.8f).dp.toPx()),
        )
    }
}

// ───────────────────────── Stamps & hanzi ─────────────────────────

/**
 * A carved seal chip — a hanzi in a square, tilted half a degree like it
 * was pressed by hand. Filled = vermillion impression; outline = carved.
 */
@Composable
fun Stamp(
    hanzi: String,
    modifier: Modifier = Modifier,
    tint: Color = InkColors.Vermillion,
    filled: Boolean = false,
    size: Dp = 34.dp,
) {
    val tilt = remember(hanzi) { ((Math.floorMod(hanzi.hashCode(), 9)) - 4) * 0.55f }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = tilt }
            .clip(InkShapes.sealAlt)
            .background(if (filled) tint else Color.Transparent)
            .border(1.5.dp, if (filled) Color.Transparent else tint, InkShapes.sealAlt),
        contentAlignment = Alignment.Center,
    ) {
        InkText(
            hanzi,
            // Two CJK glyphs at 0.52× the box are wider than the box — 达成
            // rendered as "达…". Scale by glyph count instead.
            style = InkType.Seal.copy(fontSize = (size.value * if (hanzi.length >= 2) 0.40f else 0.52f).sp),
            color = if (filled) InkColors.Bone else tint,
            maxLines = 1,
        )
    }
}

/** Giant brush hanzi sunk into the page — empty states, page heroes. */
@Composable
fun HanziWatermark(
    hanzi: String,
    modifier: Modifier = Modifier,
    fontSize: Dp = 140.dp,
    color: Color = InkColors.Bone.copy(alpha = 0.045f),
) {
    InkText(
        hanzi,
        modifier = modifier,
        style = InkType.SealHero.copy(fontSize = fontSize.value.sp, lineHeight = (fontSize.value * 1.04f).sp),
        color = color,
        maxLines = 1,
    )
}

/**
 * The one section kicker: brush hanzi first, spaced UPPERCASE label after,
 * each half in its own hand. Hand-rolled copies had the hanzi in system sans
 * or the Latin in the brush face, in both orders.
 */
@Composable
fun SectionKicker(
    hanzi: String,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = InkColors.BoneFaint,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InkText(hanzi, style = InkType.Seal.copy(fontSize = 14.sp), color = tint)
        InkText(label, style = InkType.Label, color = tint)
    }
}

// ───────────────────────── Numerals ─────────────────────────

/**
 * Editorial numeral block. Digits roll like an odometer when the value
 * changes — DOWN when growing, up when shrinking, following the flipped
 * drag gesture (swipe down = bigger, R12): the new larger digit descends
 * from the top the way the finger travels. Tabular figures keep the
 * columns still. With motion off it just swaps.
 */
@Composable
fun NumberBlock(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    style: TextStyle = InkType.NumberHuge,
    color: Color = InkColors.Bone,
    unitColor: Color = InkColors.BoneFaint,
) {
    val motion = LocalMotionEnabled.current
    val previous = remember { mutableStateOf(value) }
    val rollUp = remember(value) {
        val old = previous.value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
        val new = value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
        old == null || new == null || new >= old
    }
    SideEffect { previous.value = value }

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        if (!motion) {
            InkText(value, style = style, color = color, maxLines = 1)
        } else {
            // Stable per-position cells; only changed digits roll.
            value.forEachIndexed { index, ch ->
                if (ch.isDigit()) {
                    AnimatedContent(
                        targetState = ch,
                        transitionSpec = {
                            val enter = slideInVertically(LiliangSprings.snappy()) {
                                if (rollUp) -it else it
                            } + fadeIn(tween(90))
                            val exit = slideOutVertically(LiliangSprings.snappy()) {
                                if (rollUp) it else -it
                            } + fadeOut(tween(90))
                            enter togetherWith exit
                        },
                        label = "digit$index",
                    ) DigitCell@{ digit ->
                        InkText(digit.toString(), style = style, color = color, maxLines = 1)
                    }
                } else {
                    InkText(ch.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
        if (unit != null) {
            InkText(
                unit,
                modifier = Modifier.padding(start = InkSpacing.xs, bottom = (style.fontSize.value * 0.12f).dp),
                style = InkType.Label,
                color = unitColor,
            )
        }
    }
}

// ───────────────────────── Tallies ─────────────────────────

// ───────────────────────── Arcs ─────────────────────────

/**
 * Dry-brush progress arc: a 270° sweep that draws on first sight and turns
 * gold on completion. The streaks in the stroke come from two thin paper-
 * colored arcs dragged through it.
 */
@Composable
fun InkArc(
    progress: Float,
    modifier: Modifier = Modifier,
    key: Any = Unit,
    ledger: RevealLedger? = null,
    size: Dp = 76.dp,
    thickness: Dp = 7.dp,
    track: Color = InkColors.Bone.copy(alpha = 0.10f),
    content: @Composable (() -> Unit)? = null,
) {
    val target = progress.coerceIn(0f, 1f)
    val motion = LocalMotionEnabled.current
    val animate = (remember(key) { ledger?.shouldFire("arc:$key") ?: true }) && motion
    val shown = remember(key) { Animatable(if (animate) 0f else target) }
    LaunchedEffect(target) {
        if (animate) shown.animateTo(target, LiliangSprings.gentle()) else shown.snapTo(target)
    }
    val complete = target >= 0.999f
    val color = if (complete) InkColors.Gold else InkColors.Vermillion
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val sw = thickness.toPx()
            val inset = sw / 2 + 1.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(
                this.size.width - inset * 2,
                this.size.height - inset * 2,
            )
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            val sweep = 270f * shown.value
            if (sweep > 0.5f) {
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
                // dry-brush streaks
                val streak = androidx.compose.ui.geometry.Size(arcSize.width, arcSize.height)
                drawArc(
                    color = InkColors.PaperRaised.copy(alpha = 0.35f),
                    startAngle = 137f,
                    sweepAngle = (sweep - 4f).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = Offset(topLeft.x, topLeft.y + sw * 0.22f),
                    size = streak,
                    style = Stroke(sw * 0.16f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = InkColors.PaperRaised.copy(alpha = 0.25f),
                    startAngle = 139f,
                    sweepAngle = (sweep - 8f).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = Offset(topLeft.x, topLeft.y - sw * 0.18f),
                    size = streak,
                    style = Stroke(sw * 0.12f, cap = StrokeCap.Round),
                )
            }
        }
        if (content != null) content()
    }
}

// ───────────────────────── Gold burst ─────────────────────────

private class BurstParticle(rng: Random) {
    val angle = rng.nextFloat() * 2f * Math.PI.toFloat()
    val speed = 0.45f + rng.nextFloat() * 0.55f
    val radius = 1.5f + rng.nextFloat() * 2.6f
    val drift = (rng.nextFloat() - 0.5f) * 0.6f
    val tone = rng.nextFloat()
}

/**
 * The mastery moment: a one-shot gold-leaf burst, ~800 ms, fired whenever
 * [trigger] changes to a new non-null value. Overlay it on the celebrating
 * element; it draws nothing at rest.
 */
@Composable
fun GoldBurst(
    trigger: Any?,
    modifier: Modifier = Modifier,
    particleCount: Int = 26,
) {
    val t = remember { Animatable(1f) }
    val motion = LocalMotionEnabled.current
    LaunchedEffect(trigger) {
        if (trigger != null && motion) {
            t.snapTo(0f)
            t.animateTo(1f, tween(820, easing = LinearEasing))
        }
    }
    if (t.value >= 1f) return
    val particles = remember(trigger) {
        val rng = Random(trigger?.hashCode() ?: 1)
        List(particleCount) { BurstParticle(rng) }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val progress = t.value
        val ease = 1f - (1f - progress) * (1f - progress) // decelerate
        val center = Offset(size.width / 2, size.height / 2)
        val reach = min(size.width, size.height) * 0.52f
        val fade = (1f - progress).let { it * it * (3f - 2f * it) }
        for (p in particles) {
            val dist = reach * p.speed * ease
            val x = center.x + cos(p.angle) * dist + p.drift * dist * progress
            val y = center.y + sin(p.angle) * dist - dist * 0.18f * progress // float up
            drawCircle(
                color = lerp(InkColors.GoldBright, InkColors.Gold, p.tone).copy(alpha = fade),
                radius = p.radius.dp.toPx() * (1f - 0.4f * progress),
                center = Offset(x, y),
            )
        }
    }
}

// ───────────────────────── Ink blot ─────────────────────────

/**
 * An irregular ink blot — the calendar's day marker and the heatmap's cell.
 * Seven control points, radii seeded, so each date keeps its own blot.
 */
fun DrawScope.drawInkBlot(
    center: Offset,
    radius: Float,
    color: Color,
    seed: Int,
    alpha: Float = 1f,
) {
    val rng = Random(seed)
    val n = 7
    val radii = FloatArray(n) { radius * (0.78f + rng.nextFloat() * 0.42f) }
    val path = Path()
    for (i in 0 until n) {
        val a0 = (i.toFloat() / n) * 2f * Math.PI.toFloat()
        val a1 = ((i + 1f) / n) * 2f * Math.PI.toFloat()
        val p0 = Offset(center.x + cos(a0) * radii[i], center.y + sin(a0) * radii[i])
        val p1 = Offset(
            center.x + cos(a1) * radii[(i + 1) % n],
            center.y + sin(a1) * radii[(i + 1) % n],
        )
        val mid = Offset((p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
        if (i == 0) path.moveTo(p0.x, p0.y)
        path.quadraticBezierTo(p0.x, p0.y, mid.x, mid.y)
    }
    path.close()
    drawPath(path, color, alpha = alpha)
}

/**
 * A vermillion ink blot that blooms from [center] to flood the whole screen,
 * then fires [onCovered] — a transition into a session or day sheet. Plays
 * regardless of system animator scale (it's a deliberate transition).
 */
@Composable
fun FullScreenInkBloom(center: Offset, onCovered: () -> Unit, durationMillis: Int = 460) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, tween(durationMillis, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        onCovered()
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val maxR = kotlin.math.hypot(
            kotlin.math.max(center.x, size.width - center.x).toDouble(),
            kotlin.math.max(center.y, size.height - center.y).toDouble(),
        ).toFloat() * 1.05f
        drawInkBlot(center = center, radius = maxR * grow.value, color = InkColors.Vermillion, seed = 7, alpha = 1f)
    }
}

/**
 * A cadence tally: [target] beads on a string, [current] of them stamped as
 * filled ink blots (gold once the target is met), the rest left as hollow
 * rings. The filled beads bloom left-to-right on first composition — a count
 * that fills in like seals pressed one after another.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CadenceTally(
    current: Int,
    target: Int,
    modifier: Modifier = Modifier,
    dotSize: Dp = 16.dp,
    animate: Boolean = true,
) {
    if (target <= 0) return
    val done = current.coerceAtMost(target)
    val met = current >= target
    val fill = if (met) InkColors.Gold else InkColors.Vermillion
    val motion = LocalMotionEnabled.current
    val sweep = remember(current, target) { Animatable(if (animate && motion) 0f else done.toFloat()) }
    LaunchedEffect(current, target) {
        if (animate && motion) sweep.animateTo(done.toFloat(), tween(durationMillis = 120 + done * 90, easing = LinearEasing))
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSize * 0.45f),
        verticalArrangement = Arrangement.spacedBy(dotSize * 0.45f),
    ) {
        for (i in 0 until target) {
            val filled = i < done
            val grow = (sweep.value - i).coerceIn(0f, 1f)
            androidx.compose.foundation.Canvas(Modifier.size(dotSize)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                if (filled) {
                    // hollow ghost underneath so the bloom reads against it
                    drawCircle(InkColors.BoneFaint, radius = size.minDimension * 0.34f, center = c, style = Stroke(width = size.minDimension * 0.07f), alpha = 0.4f)
                    if (grow > 0.01f) {
                        drawInkBlot(center = c, radius = size.minDimension * 0.46f * grow, color = fill, seed = i * 31 + 5, alpha = 1f)
                    }
                } else {
                    drawCircle(InkColors.BoneFaint, radius = size.minDimension * 0.34f, center = c, style = Stroke(width = size.minDimension * 0.07f), alpha = 0.55f)
                }
            }
        }
    }
}
