package com.liliang.app.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Gold leaf catching the light. Two modes:
 *  - [goldShimmerOnce]: a single sweep when [key] first appears — PR feed
 *    rows, mastery rows, achievement stamps. The default.
 *  - [goldShimmer]: a slow ambient loop, for at most one element on screen
 *    (e.g. the dashboard's reigning PR). Off entirely when motion is off.
 *
 * The sweep is masked to the content's own pixels (SrcIn over an offscreen
 * layer), so it lights the glyphs themselves, not their bounding box.
 */

private val sheen = listOf(
    Color.Transparent,
    InkColors.GoldBright.copy(alpha = 0.55f),
    Color.White.copy(alpha = 0.35f),
    InkColors.GoldBright.copy(alpha = 0.55f),
    Color.Transparent,
)

private fun Modifier.sweepOverlay(progress: () -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val p = progress()
            if (p <= 0f || p >= 1f) return@drawWithContent
            val band = size.width * 0.6f
            val x = -band + (size.width + 2 * band) * p
            drawRect(
                brush = Brush.linearGradient(
                    colors = sheen,
                    start = Offset(x - band / 2, 0f),
                    end = Offset(x + band / 2, size.height),
                ),
                blendMode = BlendMode.SrcIn,
            )
        }

/** One sweep, ~900 ms, the first time [key] is seen by [ledger]. */
fun Modifier.goldShimmerOnce(
    key: Any,
    ledger: RevealLedger? = null,
    delayMillis: Int = 250,
): Modifier = composed {
    val arm = remember(key) { ledger?.shouldFire("shimmer:$key") ?: true }
    if (!arm) return@composed this

    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(delayMillis.toLong())
        progress.animateTo(1f, tween(900, easing = LinearEasing))
    }
    sweepOverlay { progress.value }
}

/** Ambient loop — one element per screen, max. */
fun Modifier.goldShimmer(enabled: Boolean = true): Modifier = composed {
    val motion = LocalMotionEnabled.current
    if (!enabled || !motion) return@composed this
    val transition = rememberInfiniteTransition(label = "goldShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, delayMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    sweepOverlay { progress }
}
