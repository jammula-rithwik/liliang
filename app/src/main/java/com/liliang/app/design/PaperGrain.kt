package com.liliang.app.design

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.random.Random

/**
 * The washi page. On API 33+ an AGSL shader draws paper tooth, fiber
 * mottle and a soft vignette in a single pass; earlier devices get a
 * tiled noise bitmap under the same vignette. Alpha stays ≤ 0.06 — felt,
 * not seen.
 *
 * [ambient] lets the tooth drift at ~7 fps (time uniform stepped, shader
 * path only) — reserved for at most two surfaces in the app, and stilled
 * entirely when the system animator scale is 0.
 */

/** Debug switch (gallery): force the pre-33 bitmap path to review it. */
var forcePaperFallback by mutableStateOf(false)

private const val GRAIN_ALPHA = 0.055f

private val AGSL = """
    uniform float2 uSize;
    uniform float uTime;

    half hash(float2 p) {
        p = fract(p * float2(123.34, 456.21));
        p += dot(p, p + 45.32);
        return half(fract(p.x * p.y));
    }

    half vnoise(float2 p) {
        float2 i = floor(p);
        float2 f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        half a = hash(i);
        half b = hash(i + float2(1.0, 0.0));
        half c = hash(i + float2(0.0, 1.0));
        half d = hash(i + float2(1.0, 1.0));
        return mix(mix(a, b, half(f.x)), mix(c, d, half(f.x)), half(f.y));
    }

    half4 main(float2 frag) {
        // paper tooth: per-cell speckle, drifting only via uTime
        half tooth = hash(floor(frag / 1.7) + uTime);
        // fiber mottle: two octaves of value noise
        half mottle = vnoise(frag * 0.011) * 0.65 + vnoise(frag * 0.043) * 0.35;
        // vignette: page center breathes, edges sink
        float2 uv = frag / uSize - 0.5;
        half vig = half(smoothstep(1.35, 0.4, length(uv) * 2.0));

        half lum = tooth * 0.5 + mottle * 0.5;
        half a = half(${GRAIN_ALPHA}) * (half(0.45) + half(0.55) * vig);

        half3 paper = half3(0.043, 0.039, 0.031);  // #0B0A08
        half3 bone  = half3(0.914, 0.882, 0.824);  // #E9E1D2
        return half4(mix(paper, bone, lum * a), 1.0);
    }
""".trimIndent()

@ChecksSdkIntAtLeast(api = 33)
private fun shaderSupported(): Boolean =
    Build.VERSION.SDK_INT >= 33 && !forcePaperFallback

/** 128² gray-speckle tile for the bitmap path, built once per process. */
private val noiseTile by lazy {
    val rng = Random(0x6B75616E)
    val size = 128
    val pixels = IntArray(size * size) {
        val a = rng.nextInt(0, 26) // alpha 0..25 of bone
        (a shl 24) or 0x00E9E1D2
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Root page surface. Owns the background fill, so screens place content
 * straight onto it.
 */
@Composable
fun PaperBackground(
    modifier: Modifier = Modifier,
    ambient: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = LocalMotionEnabled.current
    var time by remember { mutableIntStateOf(0) }
    val drifting = ambient && motion && shaderSupported()
    if (drifting) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(140) // ~7 fps — a paper texture, not a video
                time++
            }
        }
    }
    Box(modifier.fillMaxSize().paperGrain { if (drifting) time else 0 }) {
        // Ambient rooms (Today, the Hall) get the landscape: ink-wash ridges on
        // the horizon and auspicious clouds drifting slowly across.
        if (ambient) {
            Box(Modifier.fillMaxSize().inkMountains(seed = 7, heightFraction = 0.30f, alpha = 0.14f))
            DriftingClouds()
        }
        content()
    }
}

/** The grain itself, usable on any surface that needs to read as paper. */
fun Modifier.paperGrain(time: () -> Int = { 0 }): Modifier =
    if (shaderSupported()) shaderGrain(time) else bitmapGrain()

@RequiresApi(33)
private fun Modifier.shaderGrain(time: () -> Int): Modifier = drawWithCache {
    val shader = RuntimeShader(AGSL)
    shader.setFloatUniform("uSize", size.width, size.height)
    val brush = ShaderBrush(shader)
    onDrawBehind {
        shader.setFloatUniform("uTime", time().toFloat())
        drawRect(brush)
    }
}

private fun Modifier.bitmapGrain(): Modifier = drawWithCache {
    val tile = ShaderBrush(ImageShader(noiseTile, TileMode.Repeated, TileMode.Repeated))
    val vignette = Brush.radialGradient(
        0f to Color.Transparent,
        0.62f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.20f),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = max(size.width, size.height) * 0.78f,
    )
    onDrawBehind {
        drawRect(InkColors.Paper)
        drawRect(tile, alpha = GRAIN_ALPHA * 2.4f)
        drawRect(vignette)
    }
}

/** Gallery/debug helper so the fallback can be eyeballed on any device. */
@Composable
fun PaperFallbackSwitchEffect(force: Boolean) {
    LaunchedEffect(force) { forcePaperFallback = force }
}
