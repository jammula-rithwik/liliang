package com.liliang.app.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/*
 * Lìliàng's ornament: three traditional motifs drawn as vectors, so they stay
 * crisp at any density and cost no assets.
 *
 *  - 祥云 xiangyun, the auspicious cloud scroll — corners, headers, the
 *    slow ambient drift behind Today and the Hall;
 *  - 回纹 huiwen, the key-fret meander — corner brackets on framed cards;
 *  - 山水 shanshui, layered ink-wash mountains — the horizon under a page.
 *
 * Every motif is drawn faint. They are paper texture, not decoration to look at.
 */

// ───────────────────────── 祥云 cloud scroll ─────────────────────────

/**
 * One cloud scroll: a spiral head with a second, smaller curl and a tail
 * sweeping away. [unit] is the head's radius in px.
 */
fun DrawScope.drawXiangyun(
    center: Offset,
    unit: Float,
    color: Color,
    alpha: Float,
    flip: Boolean = false,
    strokeWidth: Float = unit * 0.16f,
) {
    val dir = if (flip) -1f else 1f
    val path = Path()
    // main spiral, outside-in
    spiral(path, center, unit, 1.55f, dir, moveTo = true)
    // the second curl sits beside the head
    val c2 = Offset(center.x + dir * unit * 1.55f, center.y + unit * 0.35f)
    spiral(path, c2, unit * 0.62f, 1.25f, -dir, moveTo = true)
    // the tail: from under the head, a long S-sweep away
    path.moveTo(center.x - dir * unit * 0.2f, center.y + unit)
    path.cubicTo(
        center.x + dir * unit * 1.2f, center.y + unit * 1.45f,
        center.x + dir * unit * 2.4f, center.y + unit * 0.55f,
        center.x + dir * unit * 3.6f, center.y + unit * 1.05f,
    )
    drawPath(
        path,
        color = color,
        alpha = alpha,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun spiral(path: Path, c: Offset, r: Float, turns: Float, dir: Float, moveTo: Boolean) {
    val steps = 40
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val a = (Math.PI * 2 * turns * (1f - t)).toFloat()
        val rr = r * (1f - 0.82f * t)
        val x = c.x + dir * rr * cos(a)
        val y = c.y + rr * sin(a)
        if (i == 0 && moveTo) path.moveTo(x, y) else path.lineTo(x, y)
    }
}

/** Faint cloud scrolls in two opposite corners of whatever this decorates. */
fun Modifier.cloudCorners(
    color: Color = InkColors.Gold,
    alpha: Float = 0.10f,
    unitDp: Float = 9f,
): Modifier = drawBehind {
    val u = unitDp.dp.toPx()
    drawXiangyun(Offset(u * 1.6f, u * 1.5f), u, color, alpha)
    drawXiangyun(Offset(size.width - u * 1.6f, size.height - u * 2.2f), u, color, alpha, flip = true)
}

/**
 * Large, very faint clouds drifting across the page. Ambient only — it runs
 * an infinite transition, so it is skipped entirely with motion off.
 */
@Composable
fun DriftingClouds(modifier: Modifier = Modifier, color: Color = InkColors.Bone, alpha: Float = 0.035f) {
    val motion = LocalMotionEnabled.current
    val drift = if (motion) {
        val t = rememberInfiniteTransition(label = "clouds")
        val v by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "drift",
        )
        v
    } else 0.5f
    Canvas(modifier.fillMaxSize()) {
        val u = size.width * 0.075f
        val dx = (drift - 0.5f) * size.width * 0.18f
        drawXiangyun(Offset(size.width * 0.18f + dx, size.height * 0.30f), u, color, alpha)
        drawXiangyun(Offset(size.width * 0.70f - dx, size.height * 0.52f), u * 1.3f, color, alpha * 0.85f, flip = true)
        drawXiangyun(Offset(size.width * 0.30f - dx * 0.6f, size.height * 0.78f), u * 0.8f, color, alpha * 0.7f)
    }
}

// ───────────────────────── 回纹 key-fret ─────────────────────────

/**
 * Key-fret brackets drawn into the four corners — the meander that frames
 * temple doors and porcelain rims. Drawn over content so it sits on top of
 * card fills.
 */
fun Modifier.fretCorners(
    color: Color = InkColors.Gold,
    alpha: Float = 0.45f,
    armDp: Float = 14f,
    insetDp: Float = 6f,
): Modifier = drawWithContent {
    drawContent()
    val a = armDp.dp.toPx()
    val inset = insetDp.dp.toPx()
    val w = 1.2.dp.toPx()
    val step = a / 4f
    fun corner(origin: Offset, sx: Float, sy: Float) {
        // Two nested L-turns stepping inward: ⌐ then a hooked return.
        val p = Path()
        p.moveTo(origin.x + sx * a, origin.y)
        p.lineTo(origin.x, origin.y)
        p.lineTo(origin.x, origin.y + sy * a)
        p.moveTo(origin.x + sx * (a - step), origin.y + sy * step)
        p.lineTo(origin.x + sx * step, origin.y + sy * step)
        p.lineTo(origin.x + sx * step, origin.y + sy * (a - step))
        p.lineTo(origin.x + sx * step * 2, origin.y + sy * (a - step))
        p.lineTo(origin.x + sx * step * 2, origin.y + sy * step * 2)
        p.lineTo(origin.x + sx * step * 3, origin.y + sy * step * 2)
        drawPath(p, color, alpha = alpha, style = Stroke(width = w, cap = StrokeCap.Square))
    }
    corner(Offset(inset, inset), 1f, 1f)
    corner(Offset(size.width - inset, inset), -1f, 1f)
    corner(Offset(inset, size.height - inset), 1f, -1f)
    corner(Offset(size.width - inset, size.height - inset), -1f, -1f)
}

// ───────────────────────── 山水 ink mountains ─────────────────────────

/**
 * Three ridgelines of diluted ink, farthest palest, filling the bottom
 * [heightFraction] of the surface. Deterministic for a given [seed].
 */
fun Modifier.inkMountains(
    seed: Int = 7,
    heightFraction: Float = 0.34f,
    color: Color = InkColors.InkWash,
    alpha: Float = 0.16f,
): Modifier = drawBehind {
    val rng = kotlin.random.Random(seed)
    val base = size.height
    val band = size.height * heightFraction
    val layers = 3
    for (layer in 0 until layers) {
        val depth = layer / (layers - 1f) // 0 = far, 1 = near
        val top = base - band * (1f - depth * 0.45f)
        val peaks = 4 + layer
        val path = Path()
        path.moveTo(0f, base)
        path.lineTo(0f, top + band * 0.25f)
        val seg = size.width / peaks
        for (i in 0 until peaks) {
            val x0 = seg * i
            val peakX = x0 + seg * (0.35f + rng.nextFloat() * 0.3f)
            val peakY = top + band * (rng.nextFloat() * 0.25f)
            val endY = top + band * (0.25f + rng.nextFloat() * 0.25f)
            path.quadraticTo(peakX - seg * 0.15f, peakY, peakX, peakY)
            path.quadraticTo(peakX + seg * 0.2f, peakY + band * 0.05f, x0 + seg, endY)
        }
        path.lineTo(size.width, base)
        path.close()
        val a = alpha * (0.45f + depth * 0.55f)
        drawPath(
            path,
            brush = Brush.verticalGradient(
                0f to color.copy(alpha = a),
                1f to color.copy(alpha = a * 0.15f),
                startY = top,
                endY = base,
            ),
        )
    }
}

// ───────────────────────── 印 seal texture ─────────────────────────

/**
 * Ink-wear speckle over a stamped seal: random pale flecks where the paste
 * didn't take, so a vermillion square reads as an impression, not a fill.
 */
fun Modifier.sealWear(seed: Int, alpha: Float = 0.22f, color: Color = InkColors.Paper): Modifier = drawWithContent {
    drawContent()
    val rng = kotlin.random.Random(seed)
    val n = 26
    repeat(n) {
        val edge = rng.nextFloat() < 0.6f
        val x: Float
        val y: Float
        if (edge) {
            // flecks cluster at the rim where real seals wear first
            val side = rng.nextInt(4)
            val t = rng.nextFloat()
            val d = rng.nextFloat() * size.minDimension * 0.08f
            when (side) {
                0 -> { x = t * size.width; y = d }
                1 -> { x = size.width - d; y = t * size.height }
                2 -> { x = t * size.width; y = size.height - d }
                else -> { x = d; y = t * size.height }
            }
        } else {
            x = rng.nextFloat() * size.width
            y = rng.nextFloat() * size.height
        }
        val r = size.minDimension * (0.008f + rng.nextFloat() * 0.022f)
        drawCircle(color, radius = r, center = Offset(x, y), alpha = alpha * (0.4f + rng.nextFloat() * 0.6f))
    }
}
