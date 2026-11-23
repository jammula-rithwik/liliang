package com.liliang.app.design

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import com.liliang.app.design.LocalMotionEnabled
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ink-wash charting. One drawing language for every chart in the app:
 * a confident line, a wash of diluted ink bleeding below it, a blurred
 * wet edge, and a vermillion scrub guide under the finger.
 *
 * The draw-on (line growing left to right, wash rising under it) fires
 * once per [key] via the shared [RevealLedger]; scroll-backs and
 * recompositions render the finished painting.
 */

data class ChartPoint(
    val value: Float,
    /** Short x label, e.g. "Mar" or "12". Empty = unlabeled. */
    val label: String = "",
    /** Full readout when scrubbed, e.g. "82.5 kg · 3 Mar". */
    val readout: String = "",
)

@Composable
fun InkAreaChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    accent: Color = InkColors.InkWash,
    height: Dp = 130.dp,
    key: Any = points.size,
    ledger: RevealLedger? = null,
    showExtremes: Boolean = true,
    onScrub: ((Int?) -> Unit)? = null,
) {
    if (points.size < 2) return
    val motion = LocalMotionEnabled.current
    val animate = (remember(key) { ledger?.shouldFire("chart:$key") ?: true }) && motion
    val reveal = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) {
        if (animate) reveal.animateTo(1f, tween(560))
    }

    var scrubIndex by remember(points) { mutableFloatStateOf(-1f) }
    val measurer = rememberTextMeasurer()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        scrubIndex = indexAt(pos.x, size.width.toFloat(), points.size)
                        onScrub?.invoke(scrubIndex.roundToInt())
                    },
                    onDragEnd = {
                        scrubIndex = -1f
                        onScrub?.invoke(null)
                    },
                    onDragCancel = {
                        scrubIndex = -1f
                        onScrub?.invoke(null)
                    },
                ) { change, _ ->
                    change.consume()
                    scrubIndex = indexAt(change.position.x, size.width.toFloat(), points.size)
                    onScrub?.invoke(scrubIndex.roundToInt())
                }
            },
    ) {
        drawInkChart(
            points = points,
            accent = accent,
            reveal = reveal.value,
            scrubIndex = scrubIndex.takeIf { it >= 0f }?.roundToInt(),
            measurer = measurer,
            showExtremes = showExtremes,
        )
    }
}

private fun indexAt(x: Float, width: Float, count: Int): Float {
    val t = (x / width).coerceIn(0f, 1f)
    return t * (count - 1)
}

private fun DrawScope.drawInkChart(
    points: List<ChartPoint>,
    accent: Color,
    reveal: Float,
    scrubIndex: Int?,
    measurer: TextMeasurer,
    showExtremes: Boolean,
) {
    val padTop = 18.dp.toPx()
    val padBottom = if (points.any { it.label.isNotEmpty() }) 18.dp.toPx() else 6.dp.toPx()
    val w = size.width
    val h = size.height - padTop - padBottom

    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val span = (max - min).takeIf { it > 0.0001f } ?: 1f

    fun xAt(i: Int) = w * i / (points.size - 1f)
    fun yAt(v: Float) = padTop + h * (1f - (v - min) / span)

    // smooth line through the points (catmull-rom -> cubic)
    val line = Path()
    for (i in points.indices) {
        val x = xAt(i)
        val y = yAt(points[i].value)
        if (i == 0) {
            line.moveTo(x, y)
        } else {
            val p0 = Offset(xAt(maxOf(i - 2, 0)), yAt(points[maxOf(i - 2, 0)].value))
            val p1 = Offset(xAt(i - 1), yAt(points[i - 1].value))
            val p2 = Offset(x, y)
            val p3 = Offset(
                xAt(minOf(i + 1, points.lastIndex)),
                yAt(points[minOf(i + 1, points.lastIndex)].value),
            )
            val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
            val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
            line.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
        }
    }

    // reveal: trim the line, clip the wash to the revealed width
    val measure = PathMeasure().apply { setPath(line, false) }
    val shownLine = if (reveal >= 1f) {
        line
    } else {
        Path().also { measure.getSegment(0f, measure.length * reveal, it, true) }
    }
    val revealRight = w * reveal

    // the wash: area under the revealed line, fading downward
    val wash = Path().apply {
        addPath(shownLine)
        lineTo(minOf(xAt(points.lastIndex), revealRight), size.height - padBottom)
        lineTo(0f, size.height - padBottom)
        close()
    }
    drawPath(
        wash,
        Brush.verticalGradient(
            0f to accent.copy(alpha = 0.30f),
            1f to accent.copy(alpha = 0.01f),
            startY = padTop,
            endY = size.height - padBottom,
        ),
    )

    // wet edge: a blurred second pass of the line
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            style = PaintingStyle.Stroke
            strokeWidth = 6.dp.toPx()
            color = accent.copy(alpha = 0.28f)
        }
        paint.asFrameworkPaint().maskFilter =
            BlurMaskFilter(7.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(shownLine, paint)
    }

    // the line itself
    drawPath(shownLine, accent, style = Stroke(2.25.dp.toPx(), cap = StrokeCap.Round))

    // terminal dot
    if (reveal >= 1f && scrubIndex == null) {
        drawCircle(
            accent,
            radius = 3.2.dp.toPx(),
            center = Offset(xAt(points.lastIndex), yAt(points.last().value)),
        )
    }

    // x labels (sparse)
    val labeled = points.withIndex().filter { it.value.label.isNotEmpty() }
    for ((i, p) in labeled) {
        val layout = measurer.measure(
            p.label,
            InkType.Label.copy(fontSize = 9.5.sp, letterSpacing = 0.4.sp),
        )
        val x = (xAt(i) - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
        drawText(layout, InkColors.BoneFaint, Offset(x, size.height - layout.size.height))
    }

    // min/max marginalia
    if (showExtremes && reveal >= 1f) {
        val maxIdx = points.indices.maxBy { points[it].value }
        val layout = measurer.measure(
            trimNumber(points[maxIdx].value),
            InkType.Label.copy(fontSize = 9.5.sp),
        )
        val x = (xAt(maxIdx) - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
        drawText(layout, InkColors.BoneDim, Offset(x, (yAt(points[maxIdx].value) - layout.size.height - 4.dp.toPx()).coerceAtLeast(0f)))
    }

    // scrub guide
    if (scrubIndex != null && scrubIndex in points.indices) {
        val x = xAt(scrubIndex)
        val y = yAt(points[scrubIndex].value)
        drawLine(
            InkColors.Vermillion.copy(alpha = 0.85f),
            start = Offset(x, padTop * 0.4f),
            end = Offset(x, size.height - padBottom),
            strokeWidth = 1.2.dp.toPx(),
        )
        // magnified point
        drawCircle(InkColors.Paper, radius = 5.5.dp.toPx(), center = Offset(x, y))
        drawCircle(InkColors.Vermillion, radius = 4.dp.toPx(), center = Offset(x, y))
        // readout tag
        val text = points[scrubIndex].readout.ifEmpty { trimNumber(points[scrubIndex].value) }
        val layout = measurer.measure(text, InkType.Label.copy(fontSize = 11.sp))
        val pad = 6.dp.toPx()
        val tagW = layout.size.width + pad * 2
        val tagH = layout.size.height + pad
        val tagX = (x - tagW / 2).coerceIn(0f, w - tagW)
        val tag = Rect(tagX, 0f, tagX + tagW, tagH)
        drawRoundRect(
            InkColors.PaperHigh,
            topLeft = tag.topLeft,
            size = tag.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
        )
        drawRoundRect(
            InkColors.Line,
            topLeft = tag.topLeft,
            size = tag.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
        drawText(layout, InkColors.Bone, Offset(tagX + pad, pad / 2))
    }
}

private fun trimNumber(v: Float): String =
    if (abs(v - v.roundToInt()) < 0.005f) v.roundToInt().toString()
    else String.format(java.util.Locale.US, "%.1f", v)

