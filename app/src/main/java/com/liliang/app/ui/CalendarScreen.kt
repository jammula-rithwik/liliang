package com.liliang.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.CalendarDaySummary
import com.liliang.app.data.CalendarDayState
import com.liliang.app.data.CalendarMarkerType
import com.liliang.app.data.CalendarMonthSnapshot
import com.liliang.app.data.MonthStats
import com.liliang.app.data.formatDistance
import com.liliang.app.data.formatDuration
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.RevealLedger
import com.liliang.app.design.drawInkBlot
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.rememberRevealLedger
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 历 — the calendar as a rice-paper scroll of months. The window is fixed
 * (two years back through three ahead), so list indices never shift; each
 * month loads as it nears the viewport and ink-fades in once. Trained days
 * are vermillion ink blots, today wears a ring, the plan shows as hollow
 * rings — the page reads at a glance.
 */
@Composable
fun CalendarScreen(viewModel: LiliangViewModel) {
    LaunchedEffect(Unit) { viewModel.ensureCalendarWindow() }
    val months = viewModel.calendarWindow
    val snapshots = viewModel.calendarSnapshots
    val ledger = rememberRevealLedger()
    if (months.isEmpty()) return

    // Day-tap bloom: an ink blot expands from the tapped cell to fill the
    // screen, and the day sheet opens behind it at full cover.
    var bloomDate by remember { mutableStateOf<String?>(null) }
    var bloomCenter by remember { mutableStateOf(Offset.Zero) }

    // Held by the ViewModel so a glance at Today doesn't lose a deep scroll
    // into old months (only the active tab stays composed).
    val listState = remember {
        viewModel.obtainCalendarListState(
            initialIndex = (months.indexOf(YearMonth.now()) + 1).coerceAtLeast(1),
        )
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = InkSpacing.l,
                end = InkSpacing.l,
                top = InkSpacing.s,
                bottom = dockClearance(),
            ),
            verticalArrangement = Arrangement.spacedBy(InkSpacing.xxl),
        ) {
            item(key = "calendar_top") {
                Column(
                    modifier = Modifier.statusBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
                ) {
                    InkText("历", style = InkType.SealHero.copy(fontSize = 44.sp), color = InkColors.Bone)
                    CalendarLegend()
                    Spacer(Modifier.height(InkSpacing.s))
                    GhostButton("↑ earlier", onClick = viewModel::extendCalendarPast, modifier = Modifier.fillMaxWidth())
                }
            }
            items(months, key = { it.toString() }) { month ->
                val snapshot = snapshots[month]
                LaunchedEffect(month, snapshot == null) {
                    if (snapshot == null) viewModel.requestCalendarMonth(month)
                }
                MonthBlock(
                    month = month,
                    snapshot = snapshot,
                    ledger = ledger,
                    onTapDay = { date, center ->
                        bloomCenter = center
                        bloomDate = date
                    },
                )
            }
        }

        if (bloomDate != null) {
            DayTapBloom(
                center = bloomCenter,
                onCovered = {
                    bloomDate?.let { viewModel.selectTodayDate(it) }
                    bloomDate = null
                },
            )
        }
    }
}

/** A vermillion blot grows from [center] to flood the screen, then fires [onCovered]. */
@Composable
private fun DayTapBloom(center: Offset, onCovered: () -> Unit) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, tween(440, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        onCovered()
    }
    Canvas(Modifier.fillMaxSize()) {
        val maxR = kotlin.math.hypot(
            maxOf(center.x, size.width - center.x).toDouble(),
            maxOf(center.y, size.height - center.y).toDouble(),
        ).toFloat() * 1.05f
        drawInkBlot(center = center, radius = maxR * grow.value, color = InkColors.Vermillion, seed = 1, alpha = 1f)
    }
}

@Composable
private fun MonthBlock(
    month: YearMonth,
    snapshot: CalendarMonthSnapshot?,
    ledger: RevealLedger,
    onTapDay: (String, Offset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            InkText(
                month.format(MONTH_FMT),
                style = InkType.Title.copy(fontSize = 24.sp),
            )
            InkText(
                month.year.toString(),
                style = InkType.NumberSmall,
                color = InkColors.BoneFaint,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (snapshot != null) {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                MonthStatsLine(snapshot.stats)
                MonthGrid(calendar = snapshot, month = month, ledger = ledger, onTapDay = onTapDay)
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                InkText("…", style = InkType.Title, color = InkColors.BoneFaint)
            }
        }
    }
}

@Composable
private fun MonthStatsLine(stats: MonthStats) {
    if (stats.trainingDays == 0) {
        InkText("— no training —", style = InkType.Label, color = InkColors.BoneFaint)
        return
    }
    InkText(
        buildString {
            append("${stats.trainingDays} DAYS")
            if (stats.liftSets > 0) append(" · ${stats.liftSets} SETS")
            if (stats.runKm > 0) append(" · ${formatDistance(stats.runKm)}")
            if (stats.martialArtsMinutes > 0) append(" · ${stats.martialArtsMinutes}M MA")
        },
        style = InkType.Label,
        color = InkColors.BoneDim,
    )
}

@Composable
private fun MonthGrid(
    calendar: CalendarMonthSnapshot,
    month: YearMonth,
    ledger: RevealLedger,
    onTapDay: (String, Offset) -> Unit,
) {
    // Sunday-start padding; this month's days only (no neighbouring edges).
    val leading = calendar.days.firstOrNull()
        ?.let { runCatching { LocalDate.parse(it.date).dayOfWeek.value % 7 }.getOrDefault(0) }
        ?: 0
    val cells: List<CalendarDaySummary?> = List(leading) { null } + calendar.days

    // Soak-in: cells ink in one after another the first time this month loads.
    val soak = remember(month) { Animatable(if (ledger.shouldFire("soak:$month")) 0f else 1f) }
    LaunchedEffect(month) {
        if (soak.value < 1f) soak.animateTo(1f, tween(durationMillis = cells.size * 18 + 350, easing = androidx.compose.animation.core.LinearEasing))
    }

    Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    InkText(label, style = InkType.Caption, color = InkColors.BoneFaint)
                }
            }
        }
        cells.chunked(7).forEachIndexed { weekIndex, week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
                week.forEachIndexed { dayIndex, day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val globalIndex = weekIndex * 7 + dayIndex
                        // A sweeping front lights cells in sequence.
                        val appear = (soak.value * (cells.size + 6) - globalIndex).coerceIn(0f, 1f)
                        DayCell(day = day, appear = appear, onTap = { c -> onTapDay(day.date, c) }, modifier = Modifier.weight(1f))
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(day: CalendarDaySummary, appear: Float, onTap: (Offset) -> Unit, modifier: Modifier = Modifier) {
    val completed = day.state == CalendarDayState.COMPLETED
    val ringColor = when {
        day.isToday -> InkColors.Vermillion
        day.state == CalendarDayState.SCHEDULED || day.state == CalendarDayState.FUTURE_SCHEDULED -> InkColors.InkWash
        day.state == CalendarDayState.DRAFT -> InkColors.Gold
        else -> null
    }
    val seed = remember(day.date) { day.date.hashCode() }
    var center by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .graphicsLayer {
                alpha = appear
                val s = 0.55f + 0.45f * appear
                scaleX = s
                scaleY = s
            }
            .clip(InkShapes.chip)
            .clickable { onTap(center) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension * 0.34f
            if (completed) {
                // soaked-in vermillion blot
                drawInkBlot(center = c, radius = r * 1.18f, color = InkColors.Vermillion, seed = seed, alpha = 0.85f)
            }
            if (day.isToday) {
                // Today is unmistakable (bone halo) but HONEST: a solid blot
                // only once trained — until then a hollow vermillion ring, so
                // trained-today and not-yet-today never look the same.
                if (!completed) {
                    drawCircle(
                        InkColors.Vermillion.copy(alpha = 0.9f),
                        radius = r * 1.05f,
                        center = c,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                drawCircle(InkColors.Bone.copy(alpha = 0.9f), radius = r * 1.42f, center = c, style = Stroke(width = 1.6.dp.toPx()))
            } else if (ringColor != null) {
                drawCircle(
                    color = ringColor.copy(alpha = 0.7f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            InkText(
                day.dayNumber.toString(),
                style = InkType.NumberSmall.copy(fontSize = 15.sp),
                color = when {
                    completed || day.isToday -> InkColors.Bone
                    day.state == CalendarDayState.NONE -> InkColors.BoneFaint
                    else -> InkColors.Bone
                },
            )
            Row(
                modifier = Modifier.height(5.dp).padding(top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                day.markers.take(3).forEach { marker ->
                    Box(Modifier.size(4.dp).clip(InkShapes.seal).background(markerInk(marker)))
                }
            }
        }
    }
}

@Composable
private fun CalendarLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            "Strength" to markerInk(CalendarMarkerType.STRENGTH),
            "Hypertrophy" to markerInk(CalendarMarkerType.HYPERTROPHY),
            "Run" to markerInk(CalendarMarkerType.RUNNING),
            "Martial" to markerInk(CalendarMarkerType.MARTIAL_ARTS),
            "Mobility" to markerInk(CalendarMarkerType.MOBILITY),
        ).forEach { (label, color) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(InkShapes.seal).background(color))
                InkText(label, style = InkType.LabelSmall, color = InkColors.BoneFaint)
            }
        }
        // The ring language, previously left for the reader to decode.
        listOf(
            "today" to InkColors.Bone,
            "scheduled" to InkColors.InkWash,
            "unfinished" to InkColors.Gold,
        ).forEach { (label, color) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(InkShapes.seal)
                        .border(1.dp, color, InkShapes.seal),
                )
                InkText(label, style = InkType.LabelSmall, color = InkColors.BoneFaint)
            }
        }
    }
}

private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM")

/** Re-inked discipline markers — distinguishable, gold reserved for PR/mastery. */
fun markerInk(marker: CalendarMarkerType): Color = when (marker) {
    CalendarMarkerType.MARTIAL_ARTS -> InkColors.Vermillion
    CalendarMarkerType.RUNNING -> InkColors.Jade
    CalendarMarkerType.STRENGTH -> InkColors.Iron
    CalendarMarkerType.HYPERTROPHY -> InkColors.InkWash
    CalendarMarkerType.MOBILITY -> InkColors.Mist
}
