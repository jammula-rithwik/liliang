package com.liliang.app.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Lìliàng's motion contract:
 *  - animation is information — every movement marks a state change;
 *  - state applies instantly, the animation follows it (never gates it);
 *  - one-shot reveals fire once per *event*, keyed on data;
 *  - when the system animator scale is 0 (user choice or battery saver),
 *    decorative motion vanishes entirely and the app is simply instant.
 */

/** False when the user has turned system animations off. */
val LocalMotionEnabled = compositionLocalOf { true }

@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(readAnimatorScale(context) > 0f)
    }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readAnimatorScale(context) > 0f
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun readAnimatorScale(context: android.content.Context): Float = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
} catch (_: Exception) {
    1f
}

object LiliangSprings {
    /** Working controls — selection, dock thumb, drag settle. Lands fast. */
    fun <T> snappy(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 1100f)

    /** Content easing in — cards, charts, crossfades. */
    fun <T> gentle(): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = 300f)

    /** Stamps and celebratory overshoot. */
    fun <T> bouncy(): SpringSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = 500f)

    /** Press scale on buttons. */
    fun <T> press(): SpringSpec<T> =
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
}

/** A spec that respects the global motion switch: instant when motion is off. */
@Composable
fun <T> AnimationSpec<T>.orSnap(): AnimationSpec<T> =
    if (LocalMotionEnabled.current) this else snap()

/**
 * Tracks which one-shot reveal keys have fired this composition lifetime,
 * so list items animate the first time their data appears and never again
 * (not on scroll-back, not on recomposition).
 */
class RevealLedger {
    private val fired = HashSet<Any>()

    /** True exactly once per key. */
    fun shouldFire(key: Any): Boolean = fired.add(key)

    fun hasFired(key: Any): Boolean = key in fired
}

@Composable
fun rememberRevealLedger(): RevealLedger = remember { RevealLedger() }

/**
 * One-shot entrance: fades up with a slight ink-spread scale. Fires when
 * [key] is first seen by [ledger] (or once per composition if no ledger),
 * after [delayMillis]. This is a content reveal, not ambient motion, so it
 * plays regardless of the system animator scale.
 */
fun Modifier.inkBleedIn(
    key: Any = Unit,
    ledger: RevealLedger? = null,
    delayMillis: Int = 0,
): Modifier = composed {
    val shouldAnimate = remember(key) { ledger?.shouldFire(key) ?: true }
    if (!shouldAnimate) return@composed this

    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis.toLong())
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }
    val p = progress.value
    val offsetPx = with(LocalDensity.current) { 14.dp.toPx() }
    // Ink resolving on paper: content starts blurred and spread, then sharpens
    // and settles. The blur is what makes it read as bleeding rather than a
    // plain fade (RenderEffect, API 31+; below it degrades to the fade+rise).
    this
        .graphicsLayer {
            alpha = (p * 1.5f).coerceAtMost(1f)
            translationY = (1f - p) * offsetPx
            val s = 0.92f + 0.08f * p
            scaleX = s
            scaleY = s
        }
        .blur(radius = ((1f - p) * 16f).dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
}

/**
 * A hanging entrance: the element drops in swinging on its cord and settles —
 * pivoting from its top edge, the way a wooden plaque hung from a beam would
 * sway and come to rest. Fires once per [key] (via [ledger]); a one-shot
 * reveal, so it plays regardless of the system animator scale.
 */
fun Modifier.swingIn(
    key: Any = Unit,
    ledger: RevealLedger? = null,
    fromDegrees: Float = 4f,
    delayMillis: Int = 0,
): Modifier = composed {
    val shouldAnimate = remember(key) { ledger?.shouldFire(key) ?: true }
    if (!shouldAnimate) return@composed this

    val rot = remember(key) { Animatable(fromDegrees) }
    val fade = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis.toLong())
        fade.animateTo(1f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(key) {
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis.toLong())
        rot.animateTo(0f, spring(dampingRatio = 0.32f, stiffness = 150f))
    }
    graphicsLayer {
        rotationZ = rot.value
        alpha = fade.value
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    }
}
