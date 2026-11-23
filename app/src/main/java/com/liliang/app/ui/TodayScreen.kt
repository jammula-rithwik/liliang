package com.liliang.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.Overlay
import com.liliang.app.data.PlanDayType
import com.liliang.app.data.WorkoutGroup
import com.liliang.app.data.WorkoutSession
import com.liliang.app.design.FullScreenInkBloom
import com.liliang.app.design.HanziWatermark
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.InkWood
import com.liliang.app.design.LocalMotionEnabled
import com.liliang.app.design.RevealLedger
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.rememberRevealLedger
import com.liliang.app.design.swingIn
import com.liliang.app.design.woodGrain
import com.liliang.app.design.cloudCorners
import com.liliang.app.design.fretCorners
import java.time.LocalDate


/**
 * 今 — the hall's threshold. You don't open a feed; you walk up and the day's
 * training hangs in front of you as a wooden menu (水牌): three plaques — 跑
 * run, 铁 the workout (centre, gilded), 武 martial — swinging on their cords.
 * A lantern overhead carries the streak: a flame that banks rest, so a normal
 * week keeps it lit. No slogans; the place itself is the motivation.
 */
@Composable
fun TodayScreen(viewModel: LiliangViewModel) {
    val ledger = rememberRevealLedger()
    // Day-aware date: an app left open past midnight moves to the new day (a
    // cheap once-a-minute check) and re-reads its world when it flips.
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val now = LocalDate.now().toString()
            if (now != today) {
                today = now
                viewModel.refreshToday()
            }
        }
    }
    val snapshot = viewModel.todaySnapshot
    // Once the snapshot is genuinely today's, trust it — its recommendation may
    // be deliberately null (workout done → sealed). The raw fallback only
    // covers the first frames before the initial load lands.
    val recommendation = if (snapshot.date == today) {
        snapshot.recommendation
    } else {
        snapshot.recommendation ?: viewModel.todayPlanRecommendation
    }
    val draftForToday = snapshot.draftSession
    val completedToday = snapshot.completedWorkouts
    // Only PAST leftovers count as unfinished business. Future-dated drafts
    // are scheduled plans (folder ingest or exploring ahead) — they belong to
    // their own day, not to today's conscience.
    val otherDrafts = viewModel.draftSessions.filter { it.workoutDate < today }
    val dailyHistory = viewModel.dashboardSnapshot.kpis.attendance.dailyHistory

    var showChooser by rememberSaveable { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val begin: (() -> Unit) -> Unit = { action -> pendingStart = action }

    val restWeekdays = remember(viewModel.configSnapshot.plans) { planRestWeekdays(viewModel.configSnapshot.plans) }
    val streak = remember(dailyHistory, restWeekdays) { computeForgivingStreak(dailyHistory, restWeekdays) }
    val banked = remember(dailyHistory, restWeekdays) { restDaysBanked(dailyHistory, restWeekdays) }
    val isRest = recommendation?.dayType == PlanDayType.REST &&
        draftForToday == null && completedToday.isEmpty()
    // A REST recommendation is a day state, not something to "begin" — only
    // non-rest recommendations drive the centre plaque's begin affordance.
    val activeRecommendation = recommendation?.takeIf { it.dayType != PlanDayType.REST }
    val recents = viewModel.plannerSnapshot.recentWorkouts
    val templates = viewModel.plannerSnapshot.templates
    // Cheap info scent for the plaque — the template's size, no engine pass.
    val planExerciseCount = activeRecommendation?.templateId
        ?.let { id -> templates.firstOrNull { it.id == id }?.items?.size }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val bloomCenter = with(LocalDensity.current) { Offset(maxWidth.toPx() / 2f, maxHeight.toPx() * 0.55f) }

        // The hall behind everything: a huge faint brush 馆 sunk into the page.
        HanziWatermark(
            "馆",
            fontSize = 300.dp,
            color = InkColors.Bone.copy(alpha = 0.04f),
            modifier = Modifier.align(Alignment.Center),
        )

        // The hanging menu wants the fixed ceremonial layout, but a short screen
        // or a huge font scale must never clip the plaques — below the threshold
        // the middle loses its weight and the whole room scrolls instead.
        val compactRoom = maxHeight < 620.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = InkSpacing.l)
                .then(if (compactRoom) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = dockClearance()),
        ) {
            Spacer(Modifier.height(InkSpacing.s))
            Beam(date = today)
            Spacer(Modifier.height(InkSpacing.l))
            Lantern(streak = streak, banked = banked, trainedToday = (dailyHistory[today] ?: 0) > 0, ledger = ledger, modifier = Modifier.align(Alignment.CenterHorizontally))

            // The menu fills the middle — the plaques are the point of the screen.
            Box(
                if (compactRoom) Modifier.fillMaxWidth().padding(vertical = InkSpacing.l) else Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
                    verticalAlignment = Alignment.Top,
                ) {
                    SidePlaque(
                        hanzi = "跑",
                        label = "Run",
                        sub = "log a run",
                        modifier = Modifier.weight(1f).swingIn(key = "pl-run", ledger = ledger, fromDegrees = -3.5f, delayMillis = 120),
                        onClick = {
                            viewModel.prepareRunQuickLog()
                            begin { viewModel.pushOverlay(Overlay.RunLog(today)) }
                        },
                    )
                    CenterPlaque(
                        draft = draftForToday,
                        completed = completedToday,
                        recommendationTitle = activeRecommendation?.let {
                            it.templateName.ifBlank { it.customWorkoutName }.ifBlank { it.dayLabel }.ifBlank { "Today" }
                        },
                        planName = activeRecommendation?.planName.orEmpty(),
                        planExerciseCount = planExerciseCount,
                        isRest = isRest,
                        modifier = Modifier.weight(1.3f).swingIn(key = "pl-mid", ledger = ledger, fromDegrees = 0f, delayMillis = 60),
                        onClick = {
                            when {
                                draftForToday != null -> begin { viewModel.openDraftSession(draftForToday.id) }
                                // Sealed day: the plaque celebrates; another round goes through the chooser.
                                completedToday.isNotEmpty() && activeRecommendation == null -> showChooser = true
                                activeRecommendation != null -> begin { viewModel.startTodayRecommended() }
                                else -> showChooser = true
                            }
                        },
                    )
                    SidePlaque(
                        hanzi = "武",
                        label = "Martial",
                        sub = "log a session",
                        modifier = Modifier.weight(1f).swingIn(key = "pl-ma", ledger = ledger, fromDegrees = 3.5f, delayMillis = 180),
                        onClick = {
                            viewModel.refreshMartialArtsForms()
                            begin { viewModel.pushOverlay(Overlay.MartialLog(today)) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(InkSpacing.m))
            Row(
                modifier = Modifier.fillMaxWidth().inkBleedIn(key = "today-alts", ledger = ledger, delayMillis = 240),
                horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
            ) {
                AltTag(hanzi = "套", label = "All saved", modifier = Modifier.weight(1f), onClick = { showChooser = true })
                AltTag(hanzi = "新", label = "Blank workout", modifier = Modifier.weight(1f), onClick = { begin { viewModel.startCustomWorkout(today) } })
            }

            if (completedToday.isNotEmpty()) {
                Spacer(Modifier.height(InkSpacing.m))
                CompletedLine(completedToday)
            }
            if (otherDrafts.isNotEmpty()) {
                Spacer(Modifier.height(InkSpacing.s))
                DraftStrip(otherDrafts) { d -> begin { viewModel.openDraftSession(d.id) } }
            }
            Spacer(Modifier.height(InkSpacing.s))
        }

        pendingStart?.let { action ->
            FullScreenInkBloom(center = bloomCenter, onCovered = {
                action()
                pendingStart = null
            })
        }
    }

    if (showChooser) {
        StartWorkoutDialog(
            title = "Start a workout",
            recentWorkouts = recents,
            templates = templates,
            onDismiss = { showChooser = false },
            onBlank = { showChooser = false; begin { viewModel.startCustomWorkout(today) } },
            onRecent = { w -> showChooser = false; begin { viewModel.startWorkoutFromRecent(w, today) } },
            onTemplate = { t -> showChooser = false; begin { viewModel.startCustomWorkoutFromTemplate(t.id, today) } },
        )
    }
}

/** The carved lintel: day + date on the left, the hall mark faint at the right. */
@Composable
private fun Beam(date: String) {
    val parsed = remember(date) { runCatching { LocalDate.parse(date) }.getOrNull() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .clip(InkShapes.card)
            .background(InkWood.Beam)
            .woodGrain(seed = 11)
            .cloudCorners(alpha = 0.12f, unitDp = 8f)
            .border(1.dp, InkColors.WoodEdge, InkShapes.card)
            .inkBleedIn(key = "today-beam")
            .padding(horizontal = InkSpacing.l, vertical = InkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            InkText(
                parsed?.dayOfWeek?.name?.lowercase()?.replaceFirstChar(Char::titlecase) ?: "Today",
                style = InkType.Masthead.copy(fontSize = 30.sp, lineHeight = 32.sp),
            )
            InkText(
                formatShortDate(date),
                style = InkType.BodyDim,
                color = InkColors.BoneDim,
            )
        }
        // (right side stays clear — the shell's 设 settings seal lives there)
    }
}

/**
 * The streak as a hanging lantern. Lit and glowing while the flame lives;
 * cold and grey once it's out. The count sits on the silk; a line beneath
 * says how much rest is banked — reassurance, not a nag.
 */
@Composable
private fun Lantern(streak: Int, banked: Int, trainedToday: Boolean, ledger: RevealLedger, modifier: Modifier = Modifier) {
    val lit = streak > 0
    val motion = LocalMotionEnabled.current
    // Create the infinite transition only when it can be seen moving — it used
    // to wake every frame on the always-open Today for a value nobody read.
    val pulse = if (motion && lit) {
        val transition = rememberInfiniteTransition(label = "lantern")
        transition.animateFloat(
            initialValue = 0.78f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1900), RepeatMode.Reverse),
            label = "pulse",
        ).value
    } else if (lit) 1f else 0.5f

    val silkTop = if (lit) Color(0xFFD14B30) else Color(0xFF4A4036)
    val silkBottom = if (lit) Color(0xFF8C2218) else Color(0xFF2A241E)
    val capColor = if (lit) InkColors.Gold else InkColors.BoneFaint

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.swingIn(key = "today-lantern", ledger = ledger, fromDegrees = 5f)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = 120.dp, height = 132.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val bodyW = 52.dp.toPx()
                val bodyTop = 26.dp.toPx()
                val bodyH = 70.dp.toPx()
                // glow
                if (lit) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                InkColors.GoldBright.copy(alpha = 0.55f * pulse),
                                InkColors.Vermillion.copy(alpha = 0.3f * pulse),
                                Color.Transparent,
                            ),
                            center = Offset(cx, bodyTop + bodyH / 2f),
                            radius = 72.dp.toPx(),
                        ),
                        radius = 72.dp.toPx(),
                        center = Offset(cx, bodyTop + bodyH / 2f),
                    )
                }
                // cord + finial
                drawLine(InkColors.BoneFaint, Offset(cx, 0f), Offset(cx, bodyTop - 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                // top cap
                drawOval(capColor, topLeft = Offset(cx - bodyW / 2f - 2.dp.toPx(), bodyTop - 6.dp.toPx()), size = Size(bodyW + 4.dp.toPx(), 11.dp.toPx()))
                // silk body
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(silkTop, silkBottom)),
                    topLeft = Offset(cx - bodyW / 2f, bodyTop),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(bodyW * 0.5f, bodyH * 0.22f),
                )
                // ribs + hoops
                val rib = (if (lit) Color(0xFF7C1F15) else Color(0xFF20120E)).copy(alpha = 0.5f)
                drawLine(rib, Offset(cx, bodyTop + 4.dp.toPx()), Offset(cx, bodyTop + bodyH - 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                drawLine(rib, Offset(cx - bodyW * 0.28f, bodyTop + 4.dp.toPx()), Offset(cx - bodyW * 0.28f, bodyTop + bodyH - 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                drawLine(rib, Offset(cx + bodyW * 0.28f, bodyTop + 4.dp.toPx()), Offset(cx + bodyW * 0.28f, bodyTop + bodyH - 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                // bottom cap
                drawOval(capColor, topLeft = Offset(cx - bodyW / 2f - 2.dp.toPx(), bodyTop + bodyH - 5.dp.toPx()), size = Size(bodyW + 4.dp.toPx(), 11.dp.toPx()))
                // tassel
                val tasselTop = bodyTop + bodyH + 6.dp.toPx()
                drawLine(capColor, Offset(cx, bodyTop + bodyH), Offset(cx, tasselTop), strokeWidth = 1.5.dp.toPx())
                val tasselColor = if (lit) InkColors.Vermillion else InkColors.BoneFaint
                for (dx in listOf(-3.dp.toPx(), 0f, 3.dp.toPx())) {
                    drawLine(tasselColor, Offset(cx + dx, tasselTop), Offset(cx + dx * 1.4f, tasselTop + 12.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            if (lit) {
                InkText(
                    streak.toString(),
                    style = InkType.NumberLarge.copy(fontSize = if (streak >= 100) 21.sp else 30.sp),
                    color = InkColors.GoldBright,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        InkText(
            flameSubtitle(streak, banked, trainedToday),
            style = InkType.Label.copy(fontSize = 11.sp),
            color = if (lit) InkColors.BoneDim else InkColors.BoneFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/** Run / Martial — a plain wooden tablet: big brush glyph, English under it. */
@Composable
private fun SidePlaque(hanzi: String, label: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    Column(
        modifier = modifier
            .heightIn(min = 196.dp)
            .clip(InkShapes.card)
            .background(InkWood.Plaque)
            .woodGrain(seed = hanzi.hashCode())
            .border(1.dp, InkColors.WoodEdge, InkShapes.card)
            .clickable { com.liliang.app.platform.Haptics.tick(view); onClick() }
            .padding(InkSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InkText(hanzi, style = InkType.SealHero.copy(fontSize = 60.sp, lineHeight = 64.sp), color = InkColors.Bone)
        Spacer(Modifier.height(InkSpacing.m))
        InkText(label, style = InkType.Title.copy(fontSize = 17.sp))
        InkText(sub, style = InkType.Caption, color = InkColors.BoneFaint)
    }
}

/** The day's workout — the gilded centre tablet. Its glyph and copy follow the
 *  day's state (resume a draft, sealed, begin the plan, rest, or choose). */
@Composable
private fun CenterPlaque(
    draft: WorkoutSession?,
    completed: List<WorkoutGroup>,
    recommendationTitle: String?,
    planName: String,
    planExerciseCount: Int?,
    isRest: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hanzi: String
    val topLabel: String
    val title: String
    val sub: String
    val hint: String
    val accent: Color
    when {
        // A coach-scheduled workout arriving on its day is a DRAFT row with no
        // logged sets — greeting it as "UNFINISHED · tap to resume" misread the
        // daily-driver flow as an abandoned session. Zero sets = not started.
        draft != null && draft.items.none { it.loggedSets.isNotEmpty() } -> {
            hanzi = "定"; topLabel = "SCHEDULED"; title = draft.name.ifBlank { "Today's workout" }
            sub = pluralize(draft.items.size, "exercise"); hint = "tap to begin ›"; accent = InkColors.Jade
        }
        draft != null -> {
            hanzi = "续"; topLabel = "UNFINISHED"; title = draft.name.ifBlank { "Today's workout" }
            sub = pluralize(draft.items.size, "exercise"); hint = "tap to resume ›"; accent = InkColors.Gold
        }
        // The day's work is done and nothing further is prescribed — the plaque
        // seals instead of offering to begin the same workout again.
        completed.isNotEmpty() && recommendationTitle == null -> {
            hanzi = "成"; topLabel = "SEALED TODAY"
            title = completed.first().displayName
            sub = if (completed.size == 1) "workout complete" else "${completed.size} workouts complete"
            hint = "tap to train again ›"; accent = InkColors.Gold
        }
        isRest -> {
            hanzi = "休"; topLabel = "TODAY"; title = "Rest"
            sub = "recovery is training"; hint = "tap to train anyway ›"; accent = InkColors.Gold
        }
        recommendationTitle != null -> {
            hanzi = "铁"; topLabel = if (planName.isNotBlank()) planName.uppercase() else "TODAY"
            title = recommendationTitle
            sub = planExerciseCount?.let { "coach ready · ${pluralize(it, "exercise")}" } ?: "today's plan"
            hint = "tap to begin ›"; accent = InkColors.Gold
        }
        else -> {
            hanzi = "铁"; topLabel = "TODAY"; title = "Workout"
            sub = "open day"; hint = "tap to choose ›"; accent = InkColors.Gold
        }
    }

    val view = androidx.compose.ui.platform.LocalView.current
    Column(
        modifier = modifier
            .heightIn(min = 228.dp)
            .clip(InkShapes.card)
            .background(InkWood.PlaquePrime)
            .woodGrain(seed = 41)
            .fretCorners(color = accent, alpha = 0.55f)
            .border(1.dp, accent.copy(alpha = 0.4f), InkShapes.card)
            .clickable { com.liliang.app.platform.Haptics.confirm(view); onClick() }
            .padding(InkSpacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InkText(topLabel, style = InkType.Label.copy(fontSize = 10.sp, letterSpacing = 2.sp), color = accent, textAlign = TextAlign.Center, maxLines = 1)
        Spacer(Modifier.height(InkSpacing.xs))
        InkText(hanzi, style = InkType.SealHero.copy(fontSize = 66.sp, lineHeight = 70.sp), color = InkColors.Bone)
        Spacer(Modifier.height(InkSpacing.xs))
        InkText(title, style = InkType.Title.copy(fontSize = 18.sp), color = InkColors.Bone, textAlign = TextAlign.Center, maxLines = 2)
        InkText(sub, style = InkType.Caption, color = InkColors.BoneDim)
        Spacer(Modifier.height(InkSpacing.xs))
        InkText(hint, style = InkType.Caption, color = accent)
    }
}

/** A slim wooden tag — alternatives to the day's plan. */
@Composable
private fun AltTag(hanzi: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    Row(
        modifier = modifier
            .clip(InkShapes.card)
            .background(InkWood.Plaque)
            .woodGrain(seed = label.hashCode())
            .border(1.dp, InkColors.WoodEdge, InkShapes.card)
            .clickable { com.liliang.app.platform.Haptics.tick(view); onClick() }
            .padding(horizontal = InkSpacing.m, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        InkText(hanzi, style = InkType.Seal.copy(fontSize = 20.sp), color = InkColors.BoneDim)
        InkText(label, style = InkType.BodyDim, color = InkColors.Bone)
    }
}

@Composable
private fun CompletedLine(completed: List<WorkoutGroup>) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            InkText("成", style = InkType.Seal.copy(fontSize = 15.sp), color = InkColors.Gold)
            InkText("SEALED TODAY", style = InkType.Label, color = InkColors.Gold)
        }
        Spacer(Modifier.height(InkSpacing.xs))
        completed.forEach { group ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                InkText(group.displayName, style = InkType.BodyDim, maxLines = 1, modifier = Modifier.weight(1f))
                InkText("${group.entries.size}", style = InkType.Label, color = InkColors.BoneFaint)
            }
        }
    }
}

/** Compact strip of drafts left open on other days. */
@Composable
private fun DraftStrip(drafts: List<WorkoutSession>, onResume: (WorkoutSession) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkText("UNFINISHED", style = InkType.Label, color = InkColors.BoneFaint)
        drafts.forEach { draft ->
            Row(
                modifier = Modifier
                    .clip(InkShapes.chip)
                    .border(1.dp, InkColors.Gold.copy(alpha = 0.4f), InkShapes.chip)
                    .clickable { onResume(draft) }
                    .padding(horizontal = InkSpacing.m, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InkText("续", style = InkType.Seal.copy(fontSize = 16.sp), color = InkColors.Gold)
                Column {
                    InkText(draft.name.ifBlank { "Workout" }, style = InkType.Body.copy(fontSize = 12.sp), maxLines = 1)
                    InkText(formatLongDate(draft.workoutDate), style = InkType.LabelSmall, color = InkColors.BoneFaint)
                }
            }
        }
    }
}

// "Trained today" is a fact read from today's history — banked == 0 also
// happens on plan-rest days (skipped without banking), and the most-read line
// in the app was asserting training that never happened.
private fun flameSubtitle(streak: Int, banked: Int, trainedToday: Boolean): String = when {
    streak <= 0 -> "cold — train today to light it"
    trainedToday -> "lit · trained today"
    banked == 0 -> "rest day — the flame holds"
    banked == 1 -> "still lit · 1 rest day in hand"
    else -> "burning low · rest holds, train tomorrow"
}
