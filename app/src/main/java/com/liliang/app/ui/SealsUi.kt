package com.liliang.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.SealCatalog
import com.liliang.app.data.SealDef
import com.liliang.app.data.SealPath
import com.liliang.app.data.SealState
import com.liliang.app.data.SealTier
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.GoldBurst
import com.liliang.app.design.HanziWatermark
import com.liliang.app.design.InkCard
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.LiliangSprings
import com.liliang.app.design.LocalMotionEnabled
import com.liliang.app.design.SectionKicker
import com.liliang.app.design.cloudCorners
import com.liliang.app.design.drawXiangyun
import com.liliang.app.design.sealWear
import com.liliang.app.platform.Haptics

// ───────────────────────── palette per tier ─────────────────────────

private val JadeInk = Color(0xFF4F8A6E)
private val JadeDeep = Color(0xFF2F5E49)
private val GoldInk = Color(0xFFC9A227)
private val GoldDeep = Color(0xFF8A6A12)
private val SumiInk = Color(0xFF1C140A)

private fun SealTier.fill(): Brush = when (this) {
    SealTier.Cinnabar -> Brush.linearGradient(listOf(Color(0xFFD5493A), InkColors.VermillionDeep))
    SealTier.Jade -> Brush.linearGradient(listOf(JadeInk, JadeDeep))
    SealTier.Gold -> Brush.linearGradient(listOf(InkColors.GoldBright, GoldInk, GoldDeep))
}

private fun SealTier.ink(): Color = when (this) {
    SealTier.Cinnabar -> InkColors.Vermillion
    SealTier.Jade -> JadeInk
    SealTier.Gold -> GoldInk
}

private fun SealTier.glyphColor(): Color = if (this == SealTier.Gold) SumiInk else InkColors.Bone

// ───────────────────────── the seal itself ─────────────────────────

/**
 * One achievement seal. Cinnabar seals are square (方印), jade seals round
 * (圆印), gold seals square with a carved double border and a cloud scroll in
 * each corner. Earned seals are pressed in solid paste with wear at the rim;
 * unearned ones are a faint carved outline with a progress arc tracing the
 * border as you close in on them.
 */
@Composable
fun SealArt(
    def: SealDef,
    earned: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    progress: Float = if (earned) 1f else 0f,
) {
    val tier = def.tier
    val round = tier == SealTier.Jade
    val tilt = remember(def.id) { ((Math.floorMod(def.id.hashCode(), 7)) - 3) * 0.8f }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = if (earned) tilt else 0f },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (earned) Modifier.sealWear(seed = def.id.hashCode(), alpha = 0.28f) else Modifier),
        ) {
            if (earned) drawEarned(tier, round) else drawCarving(tier, round, progress)
        }
        InkText(
            def.hanzi,
            style = InkType.SealHero.copy(fontSize = (size.value * 0.5f).sp, lineHeight = (size.value * 0.54f).sp),
            color = if (earned) tier.glyphColor() else InkColors.Bone.copy(alpha = 0.16f),
            maxLines = 1,
        )
    }
}

private fun DrawScope.drawEarned(tier: SealTier, round: Boolean) {
    val r = size.minDimension
    val corner = CornerRadius(r * 0.12f)
    if (round) {
        drawCircle(tier.fill(), radius = r / 2f)
        drawCircle(InkColors.Bone, radius = r / 2f - r * 0.07f, alpha = 0.55f, style = Stroke(r * 0.025f))
    } else {
        drawRoundRect(tier.fill(), cornerRadius = corner)
        val inset = r * 0.07f
        val lineColor = if (tier == SealTier.Gold) SumiInk else InkColors.Bone
        drawRoundRect(
            lineColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(r * 0.08f),
            alpha = 0.55f,
            style = Stroke(r * 0.028f),
        )
        if (tier == SealTier.Gold) {
            val inset2 = r * 0.12f
            drawRoundRect(
                SumiInk,
                topLeft = Offset(inset2, inset2),
                size = Size(size.width - inset2 * 2, size.height - inset2 * 2),
                cornerRadius = CornerRadius(r * 0.05f),
                alpha = 0.35f,
                style = Stroke(r * 0.012f),
            )
            val u = r * 0.055f
            drawXiangyun(Offset(r * 0.2f, r * 0.82f), u, SumiInk, 0.35f, strokeWidth = r * 0.012f)
            drawXiangyun(Offset(r * 0.8f, r * 0.18f), u, SumiInk, 0.35f, flip = true, strokeWidth = r * 0.012f)
        }
    }
}

private fun DrawScope.drawCarving(tier: SealTier, round: Boolean, progress: Float) {
    val r = size.minDimension
    val dash = PathEffect.dashPathEffect(floatArrayOf(r * 0.05f, r * 0.04f))
    val faint = InkColors.Bone.copy(alpha = 0.16f)
    val sw = r * 0.022f
    if (round) {
        drawCircle(faint, radius = r / 2f - sw, style = Stroke(sw, pathEffect = dash))
    } else {
        drawRoundRect(
            faint,
            topLeft = Offset(sw, sw),
            size = Size(size.width - sw * 2, size.height - sw * 2),
            cornerRadius = CornerRadius(r * 0.12f),
            style = Stroke(sw, pathEffect = dash),
        )
    }
    if (progress > 0f) {
        // The carving so far: an arc of the tier's ink, clockwise from the top.
        val inset = sw * 3
        drawArc(
            tier.ink(),
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            alpha = 0.75f,
            style = Stroke(sw * 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

// ───────────────────────── the album ─────────────────────────

@Composable
fun SealAlbumScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.checkSeals() }
    val states = viewModel.sealStates()
    val earned = states.count { it.earned }
    var selected by remember { mutableStateOf<SealState?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
            ),
            verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
        ) {
            item("top") {
                Column(Modifier.statusBarsPadding()) {
                    GhostButton("‹ Back", onClick = onBack)
                    Spacer(Modifier.height(InkSpacing.s))
                    Box(Modifier.fillMaxWidth()) {
                        HanziWatermark(
                            "印",
                            fontSize = 150.dp,
                            color = InkColors.Vermillion.copy(alpha = 0.08f),
                            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 12.dp, y = (-8).dp),
                        )
                        Column {
                            InkText("印谱", style = InkType.SealHero.copy(fontSize = 52.sp, lineHeight = 56.sp), color = InkColors.Bone)
                            InkText("THE SEAL ALBUM", style = InkType.Label, color = InkColors.Vermillion)
                            Spacer(Modifier.height(InkSpacing.m))
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                                InkText("$earned", style = InkType.NumberLarge)
                                InkText(
                                    "of ${states.size} seals carved",
                                    style = InkType.Label,
                                    color = InkColors.BoneDim,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(InkSpacing.m))
                    TierTally(states)
                    Spacer(Modifier.height(InkSpacing.m))
                    BrushDivider(seed = 88)
                }
            }
            SealPath.entries.forEach { path ->
                val inPath = states.filter { it.def.path == path }
                if (inPath.isEmpty()) return@forEach
                item("path-${path.name}") {
                    Column {
                        SectionKicker(path.hanzi, path.label.uppercase())
                        Spacer(Modifier.height(InkSpacing.m))
                        inPath.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                                row.forEach { st ->
                                    SealCell(st, Modifier.weight(1f)) { selected = st }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(InkSpacing.l))
                        }
                    }
                }
            }
        }

        selected?.let { st -> SealDetail(st, onDismiss = { selected = null }) }
    }
}

@Composable
private fun TierTally(states: List<SealState>) {
    Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
        SealTier.entries.forEach { tier ->
            val all = states.count { it.def.tier == tier }
            val got = states.count { it.def.tier == tier && it.earned }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(if (tier == SealTier.Jade) androidx.compose.foundation.shape.CircleShape else InkShapes.chip)
                        .background(tier.fill()),
                )
                InkText("${tier.label.uppercase()} $got/$all", style = InkType.Label, color = InkColors.BoneDim)
            }
        }
    }
}

@Composable
private fun SealCell(state: SealState, modifier: Modifier, onClick: () -> Unit) {
    val view = LocalView.current
    Column(
        modifier = modifier
            .clip(InkShapes.card)
            .clickable { Haptics.tick(view); onClick() }
            .semantics { contentDescription = "${state.def.title}, ${if (state.earned) "earned" else "not yet earned"}" }
            .padding(vertical = InkSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SealArt(state.def, state.earned, size = 72.dp, progress = state.progress)
        Spacer(Modifier.height(InkSpacing.s))
        InkText(
            state.def.title,
            style = InkType.Caption.copy(fontSize = 12.sp),
            color = if (state.earned) InkColors.Bone else InkColors.BoneFaint,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (!state.earned && state.progress > 0f) {
            InkText("${(state.progress * 100).toInt()}%", style = InkType.Label.copy(fontSize = 10.sp), color = state.def.tier.ink())
        }
    }
}

@Composable
private fun SealDetail(state: SealState, onDismiss: () -> Unit) {
    val motion = LocalMotionEnabled.current
    val enter = remember(state.def.id) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(state.def.id) { if (motion) enter.animateTo(1f, LiliangSprings.gentle()) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f * enter.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = (1f - enter.value) * 80.dp.toPx(); alpha = enter.value }
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(InkColors.PaperHigh)
                .cloudCorners(color = state.def.tier.ink(), alpha = 0.14f, unitDp = 10f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(InkSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SealArt(state.def, state.earned, size = 120.dp, progress = state.progress)
            Spacer(Modifier.height(InkSpacing.l))
            InkText(state.def.chineseName, style = InkType.Seal.copy(fontSize = 20.sp), color = state.def.tier.ink())
            InkText(state.def.title, style = InkType.Masthead.copy(fontSize = 26.sp, lineHeight = 30.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(InkSpacing.s))
            InkText(state.def.description, style = InkType.BodyDim, color = InkColors.BoneDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(InkSpacing.l))
            val readout = when {
                state.earned -> "Earned ${formatShortDate(state.earnedAt!!)}"
                state.def.unit.isBlank() -> "Not yet"
                else -> "${trimNumber(state.value)} of ${trimNumber(state.def.target)} ${state.def.unit}"
            }
            InkText(
                "${state.def.tier.hanzi} ${state.def.tier.label.uppercase()} · ${state.def.path.label.uppercase()} · ${readout.uppercase()}",
                style = InkType.Label,
                color = InkColors.BoneFaint,
                textAlign = TextAlign.Center,
            )
            if (!state.earned) {
                Spacer(Modifier.height(InkSpacing.m))
                ProgressBar(state.progress, state.def.tier.ink())
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(InkShapes.chip)
            .background(InkColors.Bone.copy(alpha = 0.08f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(InkShapes.chip)
                .background(color),
        )
    }
}

private fun trimNumber(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)

// ───────────────────────── the Scroll card ─────────────────────────

/** The way into the album from the Scroll: the latest seals, and the count. */
@Composable
fun SealAlbumCard(viewModel: LiliangViewModel) {
    val states = viewModel.sealStates()
    val earned = states.filter { it.earned }.sortedByDescending { it.earnedAt }
    // Show what's been pressed, then the nearest seals still being carved.
    val next = states.filter { !it.earned }.sortedByDescending { it.progress }
    val shown = (earned + next).take(5)
    InkCard(ornate = true, onClick = { viewModel.openSealAlbum() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionKicker("印", "SEAL ALBUM", tint = InkColors.BoneDim, modifier = Modifier.weight(1f))
            InkText("${earned.size}/${states.size} ›", style = InkType.Label, color = InkColors.Gold)
        }
        Spacer(Modifier.height(InkSpacing.m))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            shown.forEach { st -> SealArt(st.def, st.earned, size = 50.dp, progress = st.progress) }
        }
        Spacer(Modifier.height(InkSpacing.s))
        val hint = next.firstOrNull()?.let { "Next: ${it.def.title} · ${(it.progress * 100).toInt()}%" } ?: "Every seal carved. 宗师."
        InkText(hint, style = InkType.Caption, color = InkColors.BoneFaint)
    }
}

// ───────────────────────── the stamp-down ─────────────────────────

/**
 * Full-screen moment for a newly earned seal: the page dims, the seal drops
 * from above and presses down with a thud, gold leaf bursts, and the name
 * inks in beneath. Tap anywhere to put it in the album.
 */
@Composable
fun SealCelebration(def: SealDef, onDone: () -> Unit) {
    val motion = LocalMotionEnabled.current
    val view = LocalView.current
    val dim = remember(def.id) { Animatable(if (motion) 0f else 1f) }
    val press = remember(def.id) { Animatable(if (motion) 0f else 1f) }
    val text = remember(def.id) { Animatable(if (motion) 0f else 1f) }
    var landed by remember(def.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(def.id) {
        if (motion) {
            dim.animateTo(1f, tween(180))
            press.animateTo(1f, LiliangSprings.bouncy())
        }
        Haptics.confirm(view)
        landed = def.id
        if (motion) text.animateTo(1f, tween(380))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(InkColors.Paper.copy(alpha = 0.93f * dim.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(InkSpacing.xxl)) {
            InkText(
                "NEW SEAL · 新印",
                style = InkType.Label.copy(letterSpacing = 3.sp),
                color = def.tier.ink(),
                modifier = Modifier.graphicsLayer { alpha = text.value },
            )
            Spacer(Modifier.height(InkSpacing.xl))
            // Fixed frame: GoldBurst draws nothing (and takes no space) once it
            // finishes, which used to make the whole column jump upward.
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                GoldBurst(trigger = landed, modifier = Modifier.fillMaxSize())
                SealArt(
                    def,
                    earned = true,
                    size = 150.dp,
                    modifier = Modifier.graphicsLayer {
                        val p = press.value
                        val s = 2.2f - 1.2f * p
                        scaleX = s; scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                    },
                )
            }
            Spacer(Modifier.height(InkSpacing.xl))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = text.value; translationY = (1f - text.value) * 12.dp.toPx() },
            ) {
                InkText(def.chineseName, style = InkType.Seal.copy(fontSize = 22.sp), color = def.tier.ink())
                InkText(def.title, style = InkType.Masthead.copy(fontSize = 30.sp, lineHeight = 34.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(InkSpacing.s))
                InkText(def.description, style = InkType.BodyDim, color = InkColors.BoneDim, textAlign = TextAlign.Center)
                Spacer(Modifier.height(InkSpacing.xl))
                InkText("tap to press it into the album", style = InkType.Caption, color = InkColors.BoneFaint)
            }
        }
    }
}
