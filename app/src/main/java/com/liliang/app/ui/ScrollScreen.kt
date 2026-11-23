package com.liliang.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.DashboardKpis
import com.liliang.app.data.LiftMaxKpi
import com.liliang.app.data.MonthValue
import com.liliang.app.data.PrRecord
import com.liliang.app.data.formatDistance
import com.liliang.app.data.formatDuration
import com.liliang.app.data.formatPace
import com.liliang.app.data.formatWeight
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.CadenceTally
import com.liliang.app.design.ChartPoint
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkArc
import com.liliang.app.design.InkAreaChart
import com.liliang.app.design.InkCard
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.LocalMotionEnabled
import com.liliang.app.design.NumberBlock
import com.liliang.app.design.RevealLedger
import com.liliang.app.design.drawInkBlot
import com.liliang.app.design.goldShimmerOnce
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.rememberRevealLedger
import java.time.LocalDate

/**
 * 卷 — the scroll. The training year unrolled in ink: a streak hero, the
 * attendance heatmap and wash sparkline, goal arcs, a gold PR feed, featured
 * lifts as bleeding charts, and the year-rhythm matrices that answer "do I
 * always train less in winter?".
 */
@Composable
fun ScrollScreen(viewModel: LiliangViewModel) {
    LaunchedEffect(Unit) {
        viewModel.refreshGoalProgress()
        if (viewModel.martialArtsForms.isEmpty()) viewModel.refreshMartialArtsForms()
    }
    val kpis = viewModel.dashboardSnapshot.kpis
    val ledger = rememberRevealLedger()
    val goals = viewModel.goalProgressList

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = dockClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.xl),
    ) {
        item("hero") { Hero(kpis, viewModel.configSnapshot.plans) }
        item("attendance") { AttendancePanel(kpis, ledger) }
        item("seals") { SealAlbumCard(viewModel) }
        // Always present, even with zero goals — it's the way in to add one.
        item("goals") {
            GoalsStrip(
                goals = goals,
                onViewAll = { viewModel.openGoalsFromDashboard() },
                onAdd = { viewModel.loadGoalEditor(null) },
            )
        }
        val cadence = goals.filter { it.goal.kind == com.liliang.app.data.GoalKind.CADENCE }
        if (cadence.isNotEmpty()) {
            item("cadence") {
                CadencePanel(
                    goals = cadence,
                    ledger = ledger,
                    onViewAll = { viewModel.openGoalsFromDashboard() },
                )
            }
        }
        if (kpis.recentPrs.isNotEmpty()) {
            item("prs") { PrFeed(kpis.recentPrs, ledger, onOpen = { viewModel.selectExercise(it) }) }
        }
        if (kpis.featuredLifts.isNotEmpty()) {
            item("lifts") { FeaturedLiftsPager(kpis.featuredLifts, ledger, onOpen = { viewModel.selectExercise(it) }) }
        }
        if (kpis.monthlyTrainingDays.isNotEmpty()) {
            item("rhythm") { Seasonality(kpis, ledger) }
        }
        if (kpis.muscleMonths.isNotEmpty()) {
            item("balance") { BalancePanel(kpis) }
        }
        item("activities") { ActivitySummaries(kpis) }
    }
}

@Composable
private fun Hero(kpis: DashboardKpis, plans: List<com.liliang.app.data.WorkoutPlan>) {
    // One streak definition app-wide: the forgiving flame (see CommonUi), the
    // same number the Today lantern shows — never a second, stricter count.
    val streak = remember(kpis.attendance.dailyHistory, plans) {
        computeForgivingStreak(kpis.attendance.dailyHistory, planRestWeekdays(plans))
    }
    Column(Modifier.statusBarsPadding().inkBleedIn(key = "scroll-hero")) {
        InkText("卷", style = InkType.SealHero.copy(fontSize = 56.sp), color = InkColors.Bone)
        Spacer(Modifier.height(InkSpacing.s))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(InkSpacing.xl)) {
            Column {
                NumberBlock(value = streak.toString(), unit = "DAY STREAK", style = InkType.NumberLarge)
            }
            Column {
                NumberBlock(value = kpis.attendance.thisYear.toString(), unit = "THIS YEAR", style = InkType.NumberLarge)
            }
        }
    }
}

@Composable
private fun AttendancePanel(kpis: DashboardKpis, ledger: RevealLedger) {
    val history = kpis.attendance.dailyHistory
    // Last ~16 weeks of weekly totals for the wash sparkline.
    val weekly = remember(history) {
        val today = LocalDate.now()
        (15 downTo 0).map { w ->
            val start = today.minusWeeks(w.toLong())
            val total = (0..6).sumOf { d -> history[start.minusDays(d.toLong()).toString()] ?: 0 }
            ChartPoint(value = total.toFloat(), label = if (w % 4 == 0) "${w}w" else "", readout = "$total sessions")
        }
    }
    // Last 30 days, one tick per day — filled when trained. Self-explanatory.
    val last30 = remember(history) {
        val today = LocalDate.now()
        (29 downTo 0).map { d -> (history[today.minusDays(d.toLong()).toString()] ?: 0) > 0 }
    }
    val trainedCount = last30.count { it }
    InkCard {
        SectionLabel("ATTENDANCE", "出")
        Spacer(Modifier.height(InkSpacing.m))
        InkText("LAST 30 DAYS · $trainedCount TRAINED", style = InkType.Label, color = InkColors.BoneFaint)
        Spacer(Modifier.height(InkSpacing.s))
        DayStrip(last30)
        Spacer(Modifier.height(InkSpacing.l))
        InkText("16 WEEKS", style = InkType.Label, color = InkColors.BoneFaint)
        Spacer(Modifier.height(InkSpacing.xs))
        InkAreaChart(points = weekly, accent = InkColors.InkWash, key = "attendance", ledger = ledger, height = 120.dp)
        Spacer(Modifier.height(InkSpacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
            MiniStat("${kpis.attendance.last60Days}", "LAST 60D")
            MiniStat("${kpis.attendance.lastYearToDate}", "LY TO DATE")
            MiniStat("${kpis.attendance.lastYearTotal}", "LY TOTAL")
        }
    }
}

/** 30 ticks, one per day; trained days are filled vermillion. */
@Composable
private fun DayStrip(trained: List<Boolean>) {
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        if (trained.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val cell = (size.width - gap * (trained.size - 1)) / trained.size
        trained.forEachIndexed { i, on ->
            val x = i * (cell + gap)
            drawRoundRect(
                color = if (on) InkColors.Vermillion else InkColors.Bone.copy(alpha = 0.08f),
                topLeft = Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(cell, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun GoalsStrip(
    goals: List<com.liliang.app.engine.GoalProgress>,
    onViewAll: () -> Unit,
    onAdd: () -> Unit,
) {
    // Cadence goals get their own dot-tally panel below; arcs here are the
    // "progress toward a number" goals (lifts, runs, bodyweight, forms).
    val measured = goals.filter {
        it.goal.kind != com.liliang.app.data.GoalKind.INSPIRATION &&
            it.goal.kind != com.liliang.app.data.GoalKind.CADENCE
    }.take(3)
    InkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("GOALS", "志") }
            if (goals.isEmpty()) GhostButton("+ Set a goal", onClick = onAdd)
            else GhostButton("All →", onClick = onViewAll)
        }
        Spacer(Modifier.height(InkSpacing.m))
        if (goals.isEmpty()) {
            InkText("Nothing to train toward yet — set a goal and the coach plans around it.", style = InkType.BodyDim, color = InkColors.BoneFaint)
        } else if (measured.isEmpty()) {
            // Inspiration-only: show the words themselves, set like a motto.
            val inspiration = goals.firstOrNull { it.goal.kind == com.liliang.app.data.GoalKind.INSPIRATION }
            if (inspiration != null) {
                InkText(
                    inspiration.goal.title.ifBlank { inspiration.goal.notes },
                    style = InkType.Title.copy(fontSize = 20.sp),
                    color = InkColors.Bone,
                )
            } else {
                InkText("Tap All to see your goals.", style = InkType.BodyDim, color = InkColors.BoneFaint)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
                measured.forEach { progress ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        InkArc(progress = progress.percent, key = "gstrip:${progress.goal.id}:${(progress.percent * 100).toInt()}", size = 64.dp) {
                            InkText("${(progress.percent * 100).toInt()}%", style = InkType.NumberSmall.copy(fontSize = 13.sp), color = if (progress.isAchieved) InkColors.Gold else InkColors.Bone)
                        }
                        Spacer(Modifier.height(InkSpacing.xs))
                        InkText(progress.goal.title, style = InkType.LabelSmall, color = InkColors.BoneDim, maxLines = 2)
                    }
                }
            }
        }
    }
}

/**
 * Cadence goals on the dashboard — "run twice a week", "15 workouts a month" —
 * each shown as a string of ink beads filling in across the period. Reads in a
 * glance: how many beads are stamped vs hollow tells you if you're on pace.
 */
@Composable
private fun CadencePanel(
    goals: List<com.liliang.app.engine.GoalProgress>,
    ledger: RevealLedger,
    onViewAll: () -> Unit,
) {
    InkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("CADENCE", "期") }
            GhostButton("All →", onClick = onViewAll)
        }
        Spacer(Modifier.height(InkSpacing.m))
        goals.forEachIndexed { i, p ->
            val met = p.isAchieved
            val periodWord = if (p.goal.cadencePeriod == com.liliang.app.data.CadencePeriod.WEEK) "THIS WEEK" else "THIS MONTH"
            Column(
                Modifier
                    .fillMaxWidth()
                    .inkBleedIn(key = "dashcad:${p.goal.id}", ledger = ledger, delayMillis = i * 80),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    InkText(p.goal.title, style = InkType.Body, maxLines = 1, modifier = Modifier.weight(1f))
                    InkText("${p.currentCount}", style = InkType.NumberSmall.copy(fontSize = 17.sp), color = if (met) InkColors.Gold else InkColors.Bone)
                    InkText(" / ${p.targetCount}", style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.padding(bottom = 2.dp))
                }
                InkText(periodWord, style = InkType.LabelSmall, color = InkColors.BoneFaint)
                Spacer(Modifier.height(InkSpacing.s))
                CadenceTally(
                    current = p.currentCount,
                    target = p.targetCount,
                    dotSize = if (p.targetCount > 12) 13.dp else 18.dp,
                    animate = false,
                )
            }
            if (i < goals.lastIndex) Spacer(Modifier.height(InkSpacing.l))
        }
    }
}

@Composable
private fun PrFeed(prs: List<PrRecord>, ledger: RevealLedger, onOpen: (String) -> Unit) {
    InkCard(accent = InkColors.Gold) {
        SectionLabel("RECENT RECORDS", "印", tint = InkColors.Gold)
        Spacer(Modifier.height(InkSpacing.m))
        prs.take(6).forEachIndexed { i, pr ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.chip)
                    .clickable { onOpen(pr.exerciseName) }
                    .padding(vertical = 5.dp)
                    .goldShimmerOnce(key = "pr:${pr.exerciseName}:${pr.date}", ledger = ledger, delayMillis = 150 + i * 90),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InkText("印", style = InkType.Seal.copy(fontSize = 16.sp), color = InkColors.Gold)
                Spacer(Modifier.width(InkSpacing.m))
                Column(Modifier.weight(1f)) {
                    InkText(pr.exerciseName, style = InkType.Body, maxLines = 1)
                    InkText(formatShortDate(pr.date), style = InkType.Label, color = InkColors.BoneFaint)
                }
                val detail = buildString {
                    pr.weight?.let { append(formatWeight(it)) }
                    pr.reps?.let { append(" × $it") }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (pr.durationSeconds != null) {
                        InkText(com.liliang.app.data.formatDuration(pr.durationSeconds), style = InkType.NumberSmall.copy(fontSize = 15.sp), color = InkColors.Bone)
                        InkText("longest hold", style = InkType.Label, color = InkColors.Gold)
                    } else {
                        InkText(detail.ifBlank { "—" }, style = InkType.NumberSmall.copy(fontSize = 15.sp), color = InkColors.Bone)
                        InkText("e1RM ${formatWeight(pr.e1rm)}", style = InkType.Label, color = InkColors.Gold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedLiftsPager(lifts: List<LiftMaxKpi>, ledger: RevealLedger, onOpen: (String) -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { lifts.size })
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("FEATURED LIFTS", "铁") }
            InkText("swipe ›", style = InkType.Label, color = InkColors.BoneFaint)
        }
        Spacer(Modifier.height(InkSpacing.m))
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            pageSpacing = InkSpacing.m,
        ) { page ->
            FeaturedLift(lifts[page], ledger, onOpen = onOpen)
        }
        Spacer(Modifier.height(InkSpacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(lifts.size) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == pager.currentPage) 7.dp else 5.dp)
                        .clip(InkShapes.seal)
                        .background(if (i == pager.currentPage) InkColors.Vermillion else InkColors.Line),
                )
            }
        }
    }
}

@Composable
private fun FeaturedLift(lift: LiftMaxKpi, ledger: RevealLedger, onOpen: (String) -> Unit) {
    val points = remember(lift) {
        lift.progression.map { p ->
            ChartPoint(value = p.value.toFloat(), readout = "${formatWeight(p.value)} · ${p.date}")
        }
    }
    InkCard(onClick = { onOpen(lift.exerciseName) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                InkText(lift.exerciseName, style = InkType.Heading)
                InkText("best ${lift.lastAppearanceDate}", style = InkType.Label, color = InkColors.BoneFaint)
            }
            NumberBlock(
                value = formatWeight(lift.maxSetWeight ?: lift.maxWeight).removeSuffix(" kg"),
                unit = "KG",
                style = InkType.NumberLarge,
            )
        }
        if (points.size >= 2) {
            Spacer(Modifier.height(InkSpacing.m))
            InkAreaChart(points = points, accent = InkColors.Iron, key = "lift:${lift.exerciseName}", ledger = ledger, height = 110.dp, showExtremes = false)
        }
        if (lift.maxSetReps != null && lift.maxSetWeight != null) {
            Spacer(Modifier.height(InkSpacing.xs))
            InkText("top set ${formatWeight(lift.maxSetWeight)} × ${lift.maxSetReps}", style = InkType.Label, color = InkColors.BoneDim)
        }
    }
}

/**
 * Seasonality, redesigned: one chart, twelve months of the year on a shared
 * x-axis. Training days are vermillion wash bars; body weight is an ink line
 * over the top. Both collapse every year down to the month-of-year average,
 * so "I train less in winter — and my weight tracks it" reads in a glance,
 * the two series aligned on the same months instead of stacked blot grids.
 */
/**
 * The balance grid — strength-training days per muscle per month, last 12
 * months. Redesigned after the first gym test (30 Aug 2026): a 7×12 field of
 * alpha-varied vermillion dots read as undifferentiated noise. Now each
 * muscle owns a colour, each month is a HEIGHT-coded bar (the strongest
 * visual channel), and a quarter axis replaces twelve cryptic initials.
 * Counts strength work only — mobility drills and runs don't tip a
 * press-vs-pull balance.
 */
@Composable
private fun BalancePanel(kpis: DashboardKpis) {
    val matrix = kpis.muscleMonths
    val months = remember { val now = java.time.YearMonth.now(); (11 downTo 0L).map { now.minusMonths(it) } }
    val muscles = remember(matrix) {
        matrix.groupBy { it.muscle }.entries
            .sortedByDescending { e -> e.value.sumOf { it.days } }
            .map { it.key }
            .take(7)
    }
    val rowColors = listOf(
        InkColors.Vermillion, InkColors.Gold, InkColors.Jade,
        InkColors.Mist, InkColors.Moss, InkColors.Iron, InkColors.InkWash,
    )
    val byKey = remember(matrix) { matrix.associateBy { Triple(it.muscle, it.year, it.month) } }
    val maxDays = remember(matrix) { (matrix.maxOfOrNull { it.days } ?: 1).coerceAtLeast(1) }
    InkCard {
        SectionLabel("BALANCE", "衡")
        InkText("strength days per muscle · last 12 months", style = InkType.Label, color = InkColors.BoneFaint)
        Spacer(Modifier.height(InkSpacing.m))
        muscles.forEachIndexed { rowIndex, muscle ->
            val tint = rowColors[rowIndex % rowColors.size]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                InkText(muscle, style = InkType.BodyDim, color = tint, maxLines = 1, modifier = Modifier.width(84.dp))
                months.forEach { m ->
                    val days = byKey[Triple(muscle, m.year, m.monthValue)]?.days ?: 0
                    Box(
                        Modifier.weight(1f).height(22.dp).padding(horizontal = 1.5.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (days == 0) {
                            Box(Modifier.fillMaxWidth().height(1.5.dp).background(InkColors.Line))
                        } else {
                            val frac = (days.toFloat() / maxDays).coerceIn(0.18f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(frac)
                                    .clip(InkShapes.seal)
                                    .background(tint.copy(alpha = 0.8f)),
                            )
                        }
                    }
                }
                val sets30 = kpis.musclesHit.firstOrNull { it.muscle.equals(muscle, ignoreCase = true) }?.sets ?: 0
                InkText(
                    "$sets30",
                    style = InkType.NumberSmall.copy(fontSize = 13.sp),
                    color = InkColors.BoneDim,
                    modifier = Modifier.width(40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
        // Quarter axis — four legible month names, each at the start of its span.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(84.dp))
            months.chunked(3).forEach { quarter ->
                InkText(
                    quarter.first().month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                    style = InkType.Caption,
                    color = InkColors.BoneFaint,
                    modifier = Modifier.weight(3f),
                )
            }
            Spacer(Modifier.width(40.dp))
        }
        Spacer(Modifier.height(2.dp))
        Row { Spacer(Modifier.weight(1f)); InkText("sets · 30d", style = InkType.Caption, color = InkColors.BoneFaint) }
    }
}

@Composable
private fun Seasonality(kpis: DashboardKpis, ledger: RevealLedger) {
    val days = remember(kpis.monthlyTrainingDays) {
        (1..12).map { m ->
            kpis.monthlyTrainingDays.filter { it.month == m }.map { it.value }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat()
        }
    }
    val weight = remember(kpis.monthlyBodyweight) {
        (1..12).map { m ->
            kpis.monthlyBodyweight.filter { it.month == m && it.value > 0 }.map { it.value }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat()
        }
    }
    val animate = remember { ledger.shouldFire("seasonality") }
    val motion = LocalMotionEnabled.current
    val reveal = remember { Animatable(if (animate && motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate && motion) reveal.animateTo(1f, tween(700)) }

    InkCard {
        SectionLabel("SEASONALITY", "岁")
        InkText("every year folded into one — the shape of your training year", style = InkType.Label, color = InkColors.BoneFaint)
        Spacer(Modifier.height(InkSpacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
            LegendDot(InkColors.Vermillion, "training days")
            LegendDot(InkColors.InkWash, "body weight")
        }
        Spacer(Modifier.height(InkSpacing.m))
        SeasonalityChart(days = days, weight = weight, reveal = { reveal.value })
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(InkShapes.seal).background(color))
        InkText(label, style = InkType.LabelSmall, color = InkColors.BoneFaint)
    }
}

@Composable
private fun SeasonalityChart(days: List<Float?>, weight: List<Float?>, reveal: () -> Float) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val r = reveal()
        val labelH = 16.dp.toPx()
        val chartH = size.height - labelH
        val n = 12
        val slot = size.width / n
        val maxDays = (days.filterNotNull().maxOrNull() ?: 1f).coerceAtLeast(1f)
        val wVals = weight.filterNotNull()
        val wMin = wVals.minOrNull() ?: 0f
        val wMax = wVals.maxOrNull() ?: 1f
        val wSpan = (wMax - wMin).takeIf { it > 0.01f } ?: 1f

        // training-day wash bars
        for (m in 0 until n) {
            val d = days[m] ?: continue
            val barH = chartH * (d / maxDays) * r
            val barW = slot * 0.5f
            val x = slot * m + (slot - barW) / 2f
            drawRoundRect(
                color = InkColors.Vermillion.copy(alpha = 0.28f),
                topLeft = Offset(x, chartH - barH),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }

        // body-weight line over the top, normalised to its own range
        val pts = (0 until n).mapNotNull { m -> weight[m]?.let { m to it } }
        if (pts.size >= 2) {
            fun px(m: Int) = slot * m + slot / 2f
            fun py(v: Float) = (chartH * 0.12f) + (chartH * 0.76f) * (1f - (v - wMin) / wSpan)
            val path = Path()
            pts.forEachIndexed { i, (m, v) ->
                val x = px(m)
                val y = py(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val shown = Path().also { measure.getSegment(0f, measure.length * r, it, true) }
            drawPath(shown, InkColors.InkWash, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
            if (r > 0.98f) {
                pts.forEach { (m, v) -> drawCircle(InkColors.InkWash, radius = 2.6.dp.toPx(), center = Offset(px(m), py(v))) }
            }
        }

        // month initials
        val months = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
        months.forEachIndexed { m, mm ->
            val layout = measurer.measure(mm, InkType.LabelSmall)
            drawText(layout, color = InkColors.BoneFaint, topLeft = Offset(slot * m + (slot - layout.size.width) / 2f, chartH + 2.dp.toPx()))
        }
    }
}

@Composable
private fun ActivitySummaries(kpis: DashboardKpis) {
    Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
        InkCard(modifier = Modifier.weight(1f), accent = InkColors.Jade) {
            SectionLabel("RUNNING", "跑", tint = InkColors.Jade)
            Spacer(Modifier.height(InkSpacing.s))
            InkText(formatDistance(kpis.running.thisYearDistanceKm), style = InkType.NumberSmall.copy(fontSize = 20.sp))
            InkText("this year · ${kpis.running.thisYearRuns} runs", style = InkType.Label, color = InkColors.BoneFaint)
            kpis.running.fastestPaceSecondsPerKm?.let {
                InkText("best pace ${formatPace(it)}", style = InkType.Label, color = InkColors.BoneDim)
            }
        }
        InkCard(modifier = Modifier.weight(1f), accent = InkColors.Vermillion) {
            SectionLabel("MARTIAL", "武", tint = InkColors.Vermillion)
            Spacer(Modifier.height(InkSpacing.s))
            InkText("${kpis.martialArts.totalThisYearSessions}", style = InkType.NumberSmall.copy(fontSize = 20.sp))
            InkText("sessions this year", style = InkType.Label, color = InkColors.BoneFaint)
        }
    }
}

@Composable
private fun SectionLabel(text: String, hanzi: String, tint: Color = InkColors.BoneDim) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
        InkText(hanzi, style = InkType.Seal.copy(fontSize = 18.sp), color = tint)
        InkText(text, style = InkType.Label, color = tint)
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column {
        InkText(value, style = InkType.NumberSmall.copy(fontSize = 18.sp))
        InkText(label, style = InkType.LabelSmall, color = InkColors.BoneFaint)
    }
}

