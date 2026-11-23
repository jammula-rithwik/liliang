package com.liliang.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import com.liliang.app.BagPlan
import com.liliang.app.LiliangViewModel
import com.liliang.app.RoundTimerOrigin
import com.liliang.app.RoundTimerSetup
import com.liliang.app.data.ComboItem
import com.liliang.app.data.ExerciseCatalogItem
import com.liliang.app.data.LogFormat
import com.liliang.app.data.MartialArtsForm
import com.liliang.app.data.MovementType
import com.liliang.app.data.PerformanceHint
import com.liliang.app.data.ProgressionRole
import com.liliang.app.data.SupersetTag
import com.liliang.app.data.isPerSide
import com.liliang.app.data.supersetTags
import com.liliang.app.data.WeightUnit
import com.liliang.app.data.WorkoutSession
import com.liliang.app.data.WorkoutSessionItem
import com.liliang.app.data.WorkoutSessionSet
import com.liliang.app.data.formatDistance
import com.liliang.app.data.formatDuration
import com.liliang.app.data.formatPace
import com.liliang.app.data.parseLeadingDouble
import com.liliang.app.data.parseLeadingInt
import com.liliang.app.data.supportsCombos
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.GoldBurst
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.LocalMotionEnabled
import com.liliang.app.design.NumberBlock
import com.liliang.app.design.SealButton
import com.liliang.app.design.Stamp
import com.liliang.app.design.goldShimmerOnce
import com.liliang.app.engine.epleyE1rm
import com.liliang.app.platform.Haptics
import com.liliang.app.platform.TtsEngine
import com.liliang.app.platform.VoiceNoteRecognizer
import com.liliang.app.platform.WorkoutBell
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The forge, redesigned as **focus mode**: one exercise fills the screen, you
 * swipe between them, and a single working set sits centre-stage — dial the
 * weight and reps by dragging, then press the seal to log it. Committed sets
 * drop into an ink ledger below. Nothing like v2's editable table.
 */
@Composable
fun SessionScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val session = viewModel.currentSession ?: return
    val config = viewModel.configSnapshot
    val canComplete = viewModel.canCompleteCurrentSession()

    var showAddExercise by rememberSaveable { mutableStateOf(false) }
    var pendingNewExerciseName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoveItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var sealing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (viewModel.martialArtsForms.isEmpty()) viewModel.refreshMartialArtsForms()
    }

    // Page 0 is the workout's title board — what today holds and why — then
    // one page per exercise, then the add page.
    val pageCount = session.items.size + 2
    // A blank custom workout opens on the add page — a title board with
    // nothing under it helps nobody. Planned workouts open on the board.
    val pagerState = rememberPagerState(initialPage = if (session.items.isEmpty()) 1 else 0, pageCount = { pageCount })
    val pagerScope = rememberCoroutineScope()
    // Day-tick-aware: a session opened before midnight must unlock its seal and
    // drop the "scheduled" banner when the day arrives, not on re-entry.
    var todayIso by remember { mutableStateOf(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            todayIso = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        }
    }
    // A session dated ahead is a plan being browsed, not a workout being done:
    // no clock, no logging, no seal — those open on the day.
    val scheduledAhead = session.workoutDate > todayIso
    // Superset groups by letter and colour, for the board and each page (R20).
    val supersets = remember(session.items) { supersetTags(session.items) }

    // navigationBarsPadding keeps the set ledger's last row clear of the
    // device's own bar — on a 3-button Samsung the bottom row was unreadable
    // underneath it (Greg, 29 Aug 2026).
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Slim top bar — back, workout title (tap = details), finish seal.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = InkSpacing.l, vertical = InkSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton("‹ Back", onClick = onBack)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = InkSpacing.m).clip(InkShapes.chip).clickable { showDetails = true },
            ) {
                InkText(session.name.ifBlank { "Workout" }, style = InkType.Heading, maxLines = 1)
                InkText(
                    if (canComplete) "${pluralize(session.items.size, "exercise")} · tap for details" else "nothing sealed yet · tap for details",
                    style = InkType.Label,
                    color = InkColors.BoneFaint,
                    maxLines = 1,
                )
            }
            SealButton("印", onClick = { sealing = true }, enabled = canComplete && !scheduledAhead, compact = true)
        }

        if (scheduledAhead) {
            ScheduledBanner(date = session.workoutDate)
        } else {
            SessionTimingBar(startedAtMillis = session.startedAtMillis)
        }

        // Page rail — which exercise, and a hint to swipe.
        if (session.items.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = InkSpacing.l, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.height(3.dp).weight(0.5f).clip(InkShapes.seal)
                        .background(if (pagerState.currentPage == 0) InkColors.Jade else InkColors.Line),
                )
                session.items.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .height(3.dp)
                            .weight(1f)
                            .clip(InkShapes.seal)
                            .background(if (i + 1 == pagerState.currentPage) InkColors.Vermillion else InkColors.Line),
                    )
                }
                Box(
                    Modifier.height(3.dp).weight(0.5f).clip(InkShapes.seal)
                        .background(if (pagerState.currentPage == pageCount - 1) InkColors.Gold else InkColors.Line),
                )
            }
        }

        // The pager locks while a timed hold is counting — a stray horizontal
        // fling would dispose the hold's clock (no ticks, no finish bell) with
        // his eyes off the phone.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = !viewModel.holdTimerActive,
        ) { page ->
            if (page == 0) {
                WorkoutTitleStage(
                    session = session,
                    scheduledAhead = scheduledAhead,
                    exercises = config.exercises,
                    supersets = supersets,
                    onJumpToExercise = { i ->
                        pagerScope.launch { pagerState.animateScrollToPage(i + 1) }
                    },
                )
            } else if (page - 1 < session.items.size) {
                val item = session.items[page - 1]
                // No pre-seeded empty sets — the ledger starts empty and fills
                // only when you press Log set, so there's never a blank "—" row.
                ExerciseStage(
                    item = item,
                    index = page - 1,
                    total = session.items.size,
                    exercise = config.exercises.firstOrNull { it.name == item.exerciseName },
                    combos = config.combos,
                    forms = viewModel.martialArtsForms,
                    coachEnabled = config.coachEnabled,
                    restChime = config.restChimeEnabled,
                    viewModel = viewModel,
                    superset = supersets[item.id],
                    onRequestRemove = { pendingRemoveItemId = item.id },
                )
            } else {
                AddExercisePage(onAdd = { showAddExercise = true })
            }
        }
    }

    if (showDetails) {
        SessionDetailsDialog(
            session = session,
            onDismiss = { showDetails = false },
            onNameChange = { v -> viewModel.updateCurrentSession { it.copy(name = v) } },
            onUpdate = { updated -> viewModel.updateCurrentSession { updated } },
        )
    }
    if (showAddExercise) {
        SearchSelectionDialog(
            title = "Add exercise",
            options = orderedExerciseOrMuscleOptions(config.exercises, config.muscleGroups),
            allowCustomEntry = true,
            customLabel = "Exercise or muscle group",
            onDismiss = { showAddExercise = false },
            onSelect = { selected ->
                showAddExercise = false
                val known = config.exercises.any { it.name.equals(selected, ignoreCase = true) } ||
                    config.muscleGroups.any { it.equals(selected, ignoreCase = true) }
                if (known) viewModel.addSessionExercise(selected) else pendingNewExerciseName = selected
            },
        )
    }
    pendingNewExerciseName?.let { newName ->
        NewExerciseDialog(
            initialName = newName,
            muscles = config.muscleGroups,
            categories = config.categories,
            onDismiss = { pendingNewExerciseName = null },
            onJustAdd = {
                viewModel.addSessionExercise(newName)
                pendingNewExerciseName = null
            },
            onSave = { item ->
                viewModel.createExerciseAndAddToSession(item)
                pendingNewExerciseName = null
            },
        )
    }
    pendingRemoveItemId?.let { id ->
        val name = session.items.firstOrNull { it.id == id }?.title?.ifBlank { "this exercise" } ?: "this exercise"
        ConfirmationDialog(
            title = "Remove exercise?",
            message = "Removes $name from today's workout. Your history isn't touched.",
            confirmLabel = "Remove",
            onDismiss = { pendingRemoveItemId = null },
            onConfirm = {
                viewModel.removeSessionItem(id)
                pendingRemoveItemId = null
            },
        )
    }
    if (sealing) {
        SealPressOverlay(onComplete = {
            viewModel.completeCurrentSession()
            onBack()
        })
    }
}

/**
 * Slim timing strip under the top bar: total session time on the left. The
 * rest readout that used to sit small on the right moved into each exercise
 * stage — [ExerciseRestBar] shows it big, next to that exercise's planned
 * rest (Greg, 29 Aug 2026: phone on the floor, the 15sp figure was
 * unreadable standing up). The one-second clock lives HERE so only this
 * strip recomposes on tick, not the whole session screen.
 */
@Composable
private fun SessionTimingBar(startedAtMillis: Long?) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        if (startedAtMillis == null) return@LaunchedEffect // nothing to count
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val sessionSecs = startedAtMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = InkSpacing.l, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sessionSecs != null) {
            InkText("SESSION ", style = InkType.Label, color = InkColors.BoneFaint)
            InkText(formatSessionClock(sessionSecs), style = InkType.NumberSmall.copy(fontSize = 14.sp), color = InkColors.BoneDim)
        }
    }
}

private fun Modifier.androidxSemantics(label: String): Modifier =
    semantics { this.contentDescription = label }

private fun formatSessionClock(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * "SET 3 OF 4" in the Label position above the dials — the one fact the floor
 * couldn't read (the ledger is 15sp inside a scroll window). Moss inside the
 * plan, gold on the final planned set, faint once past the plan.
 */
@Composable
private fun SetProgressLine(logged: Int, planned: Int?) {
    val next = logged + 1
    val text = when {
        planned == null -> "SET $next"
        logged >= planned -> "PLAN DONE · SET $next"
        else -> "SET $next OF $planned"
    }
    val color = when {
        planned == null -> InkColors.BoneDim
        logged >= planned -> InkColors.BoneFaint
        next == planned -> InkColors.Gold
        else -> InkColors.Moss
    }
    InkText(text, style = InkType.Label, color = color, modifier = Modifier.padding(vertical = 2.dp))
}

/**
 * The rest line for one exercise, under its LAST row: the plan's prescribed
 * rest on the left, and on the right a count-up since the last logged set of
 * THIS exercise — big enough to read from the floor, gold while inside the
 * plan, vermillion once past it. Scoped per exercise on purpose: arriving at
 * a fresh exercise shows a quiet "—", because the gap since the previous
 * exercise's last set is transition (racking up, moving stations), not rest —
 * Greg's 29 Aug 2026 call, chosen over a "next exercise started" button. The
 * honest per-set record is `loggedAtMillis`, which the Sunday review reads.
 * Own one-second clock so ticking recomposes only this row.
 */
@Composable
private fun ExerciseRestBar(planSeconds: Int?, lastLoggedAtMillis: Long?, chime: Boolean = false) {
    if (planSeconds == null && lastLoggedAtMillis == null) return
    val view = LocalView.current
    val bell = remember { com.liliang.app.platform.WorkoutBell() }
    DisposableEffect(Unit) { onDispose { bell.release() } }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One audible tick + buzz the moment the count-up crosses the plan — the
    // vermillion flip alone happens on a screen lying on the floor. Fires once
    // per logged set, and only when the crossing is WATCHED live (opening an
    // exercise whose rest lapsed long ago stays quiet).
    var chimedFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lastLoggedAtMillis) {
        if (lastLoggedAtMillis == null) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            if (chime && planSeconds != null && chimedFor != lastLoggedAtMillis) {
                val elapsed = (nowMillis - lastLoggedAtMillis) / 1000L
                if (elapsed >= planSeconds && elapsed < planSeconds + 30) {
                    chimedFor = lastLoggedAtMillis
                    bell.tick()
                    Haptics.confirm(view)
                } else if (elapsed >= planSeconds + 30) {
                    // Too late to be a live crossing — mark handled, stay quiet.
                    chimedFor = lastLoggedAtMillis
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    val restSecs = lastLoggedAtMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0) }
    val over = planSeconds != null && restSecs != null && restSecs > planSeconds
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = InkSpacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            InkText("PLANNED REST", style = InkType.LabelSmall, color = InkColors.BoneFaint)
            InkText(
                // Prose form ("1m 30s") — the same field the title board prints;
                // the clock face belongs to live counters only.
                planSeconds?.let { formatDuration(it) } ?: "—",
                style = InkType.NumberSmall,
                color = InkColors.BoneDim,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            InkText(
                "REST",
                style = InkType.LabelSmall,
                color = when {
                    restSecs == null -> InkColors.BoneFaint
                    over -> InkColors.Vermillion
                    else -> InkColors.Gold
                },
            )
            InkText(
                restSecs?.let { formatSessionClock(it) } ?: "—",
                style = InkType.NumberSmall.copy(fontSize = 34.sp, lineHeight = 36.sp),
                color = when {
                    restSecs == null -> InkColors.BoneFaint
                    over -> InkColors.Vermillion
                    else -> InkColors.Gold
                },
            )
        }
    }
}

// ───────────────────────── Per-exercise stage ─────────────────────────

@Composable
private fun ExerciseStage(
    item: WorkoutSessionItem,
    index: Int,
    total: Int,
    exercise: ExerciseCatalogItem?,
    combos: List<ComboItem>,
    forms: List<MartialArtsForm>,
    coachEnabled: Boolean,
    restChime: Boolean,
    viewModel: LiliangViewModel,
    superset: SupersetTag? = null,
    onRequestRemove: () -> Unit,
) {
    val category = exercise?.defaultCategory?.trim()?.lowercase().orEmpty()
    val martialArts = category == "martial arts" ||
        forms.any { it.discipline.equals(item.exerciseName.trim(), ignoreCase = true) }
    val running = category == "running"
    val accent = InkColors.forCategory(exercise?.defaultCategory)

    var showRolePicker by rememberSaveable(item.id) { mutableStateOf(false) }

    // Spoken notes (R20): the 记 seal listens for a note on the set he's on,
    // so the thing noticed mid-set is said now, not forgotten on the drive
    // home. The microphone is asked for the first time it's needed.
    val context = LocalContext.current
    var noteSheet by remember(item.id) { mutableStateOf(false) }
    var typingNote by remember(item.id) { mutableStateOf(false) }
    var editingPending by remember(item.id) { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            noteSheet = true
        } else {
            viewModel.notice("Microphone is off for Lìliàng — type the note instead")
            typingNote = true
        }
    }
    val openNote: () -> Unit = {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            noteSheet = true
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = InkSpacing.l)) {
        // Header — long-press the title to remove this exercise.
        Row(
            modifier = Modifier.pointerInput(item.id) { detectTapGestures(onLongPress = { onRequestRemove() }) },
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                // Role chip — tap to set primary / secondary / accessory. The
                // visual chip stays small; the touch target pads to 44dp.
                Box(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { showRolePicker = true },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .clip(InkShapes.chip)
                            .border(1.dp, accent.copy(alpha = 0.5f), InkShapes.chip)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        InkText(
                            item.progressionRole.takeIf { it != ProgressionRole.NONE }?.label?.uppercase() ?: "SET ROLE",
                            style = InkType.LabelSmall,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                InkText(item.title.ifBlank { "Exercise" }, style = InkType.Masthead.copy(fontSize = 30.sp), maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkText("${index + 1} of $total", style = InkType.LabelSmall, color = InkColors.BoneFaint)
                    // A superset partner is named in its group's colour, so the
                    // next station is on the page he's already looking at.
                    if (superset != null) {
                        Spacer(Modifier.width(InkSpacing.s))
                        SupersetBadge(superset, size = 18.dp)
                        Spacer(Modifier.width(InkSpacing.xs))
                        InkText(
                            "with ${superset.partners.joinToString(" · ")}",
                            style = InkType.LabelSmall,
                            color = InkColors.superset(superset.index),
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(
                Modifier.size(44.dp).clip(InkShapes.chip)
                    .androidxSemantics("Record a note for this set")
                    .clickable(onClick = openNote),
                contentAlignment = Alignment.Center,
            ) { InkText("记", style = InkType.Seal.copy(fontSize = 20.sp), color = InkColors.Vermillion) }
            Box(
                Modifier.size(44.dp).clip(InkShapes.chip)
                    .androidxSemantics("Remove exercise")
                    .clickable(onClick = onRequestRemove),
                contentAlignment = Alignment.Center,
            ) { InkText("✕", style = InkType.Body, color = InkColors.BoneFaint) }
        }
        // Two voices can speak here: the engine's rationale (gated on the coach
        // toggle) and a written prescription note from the plan or an ingested
        // ingest — always shown, because it was written for this exact workout.
        val coachLines = buildList {
            if (coachEnabled && item.coachNote.isNotBlank()) add(item.coachNote)
            if (item.notes.isNotBlank() && item.notes != item.coachNote) add(item.notes)
        }
        if (coachLines.isNotEmpty()) {
            Spacer(Modifier.height(InkSpacing.s))
            CoachNote(coachLines.joinToString("\n"))
        }
        if (item.pendingComment.isNotBlank()) {
            Spacer(Modifier.height(InkSpacing.s))
            PendingNoteLine(item, onClick = { editingPending = true })
        }
        Spacer(Modifier.height(InkSpacing.s))
        BrushDivider(seed = item.id.toInt())

        // The log stamp overlay spans the whole stage so it can stamp at the
        // numbers and fly down into the ledger. logTick + PR flag are hoisted.
        var logTick by remember(item.id) { mutableIntStateOf(0) }
        var logWasPr by remember(item.id) { mutableStateOf(false) }
        // Timed holds (plank, scapular hang) get the hold stage: recognised by
        // the exercise's log format, an explicit durationSeconds target, or a
        // legacy "20s"-style reps prescription from older plans.
        val holdSeconds = durationTargetSeconds(item, exercise)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                martialArts -> MartialStage(item, exercise, forms, combos, viewModel, Modifier.fillMaxSize()) { logWasPr = false; logTick++ }
                running -> RunningStage(item, viewModel, Modifier.fillMaxSize()) { logWasPr = false; logTick++ }
                holdSeconds != null || (exercise != null && exercise.logFormat != LogFormat.REPS) ->
                    DurationStage(item, holdSeconds, isPerSide(item, exercise), restChime, viewModel, Modifier.fillMaxSize()) { logWasPr = false; logTick++ }
                else -> StrengthStage(item, exercise, restChime, viewModel, Modifier.fillMaxSize()) { pr -> logWasPr = pr; logTick++ }
            }
            LogStampOverlay(tick = logTick, gold = logWasPr, modifier = Modifier.fillMaxSize())
        }
    }

    if (showRolePicker) {
        InkDialog(title = "Role for ${item.title.ifBlank { "this exercise" }}", onDismiss = { showRolePicker = false }) {
            InkText("How the coach treats this lift — primaries get the heavy programming.", style = InkType.BodyDim, color = InkColors.BoneFaint)
            listOf(ProgressionRole.PRIMARY, ProgressionRole.SECONDARY, ProgressionRole.ACCESSORY, ProgressionRole.NONE).forEach { role ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InkShapes.chip)
                        .then(if (item.progressionRole == role) Modifier.border(1.dp, InkColors.Vermillion, InkShapes.chip) else Modifier)
                        .clickable {
                            viewModel.updateSessionItem(item.id) { it.copy(progressionRole = role) }
                            showRolePicker = false
                        }
                        .padding(InkSpacing.m),
                ) {
                    InkText(role.label, style = InkType.BodyStrong, color = if (item.progressionRole == role) InkColors.Vermillion else InkColors.Bone)
                }
            }
            GhostButton("Cancel", onClick = { showRolePicker = false })
        }
    }
    if (noteSheet) {
        VoiceNoteSheet(
            target = noteTarget(item),
            onHeard = { text ->
                viewModel.addSetNote(item.id, text)
                noteSheet = false
            },
            onType = {
                noteSheet = false
                typingNote = true
            },
            onDismiss = { noteSheet = false },
        )
    }
    if (typingNote) {
        NoteEditDialog(
            title = "Note · ${noteTarget(item)}",
            initial = "",
            onSave = { text ->
                viewModel.addSetNote(item.id, text)
                typingNote = false
            },
            onDelete = null,
            onDismiss = { typingNote = false },
        )
    }
    if (editingPending) {
        NoteEditDialog(
            title = "Note · ${noteTarget(item)}",
            initial = item.pendingComment,
            onSave = { text ->
                viewModel.setPendingNote(item.id, text)
                editingPending = false
            },
            onDelete = {
                viewModel.setPendingNote(item.id, "")
                editingPending = false
            },
            onDismiss = { editingPending = false },
        )
    }
}

@Composable
private fun StrengthStage(
    item: WorkoutSessionItem,
    exercise: ExerciseCatalogItem?,
    restChime: Boolean,
    viewModel: LiliangViewModel,
    modifier: Modifier = Modifier,
    onLogged: (isPr: Boolean) -> Unit,
) {
    val view = LocalView.current
    val last = item.loggedSets.lastOrNull()
    // What the stack in front of him is labelled in. Half the gym is marked in
    // pounds, so this is per-exercise and it sticks — set it once at the machine.
    val unit = exercise?.weightUnit ?: WeightUnit.KG
    val isolation = exercise?.movementType == MovementType.ISOLATION
    // The dial steps in whatever unit is being READ, so the numerals stay round:
    // 2.5/1.25 on a kilo stack, 5/2.5 on a pound one.
    val step = when {
        unit == WeightUnit.LB -> if (isolation) 2.5 else 5.0
        else -> if (isolation) 1.25 else 2.5
    }
    val bodyweight = exercise?.isBodyweight == true
    // Keyed on the targets too, so a fresh engine prescription reseeds the
    // dials instead of showing last week's numbers (audit #31).
    var weight by rememberSaveable(item.id, item.targetWeight, item.targetReps) {
        mutableDoubleStateOf(last?.weight ?: item.targetWeight ?: if (bodyweight) 0.0 else 20.0)
    }
    var reps by rememberSaveable(item.id, item.targetWeight, item.targetReps) {
        mutableIntStateOf(parseLeadingInt(last?.repsText) ?: parseLeadingInt(item.targetReps) ?: 5)
    }
    var typeWeight by rememberSaveable(item.id) { mutableStateOf(false) }
    var typeReps by rememberSaveable(item.id) { mutableStateOf(false) }

    // The PR bar includes what's already been logged THIS session — the same
    // top set must not stamp gold twice in one workout (audit #21). Weight 0
    // (bodyweight) has no e1RM, so it can never fake a PR (audit #23).
    val sessionBestE1rm = item.loggedSets
        .mapNotNull { s -> epleyE1rm(s.weight, parseLeadingInt(s.repsText)) }
        .maxOrNull()
    val prBaseline = listOfNotNull(item.hints.bestE1rm, sessionBestE1rm).maxOrNull()
    val e1rm = if (weight > 0) epleyE1rm(weight, reps) else null
    val isPr = prBaseline != null && e1rm != null && e1rm > prBaseline * 1.0015

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HintRow(item.hints, unit = unit) { w, r -> weight = w; reps = r }
        ExerciseRestBar(
            planSeconds = item.targetRestSeconds,
            lastLoggedAtMillis = item.loggedSets.mapNotNull { it.loggedAtMillis }.maxOrNull(),
            chime = restChime,
        )
        SetProgressLine(logged = item.loggedSets.size, planned = item.plannedSets)

        // The stage — weight × reps as twin draggable numerals, same size and
        // baseline so they read as one line. Drag to dial; tap to type exact.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                WorkingNumber(
                    label = unit.label,
                    // `weight` is always kilograms; only the reading changes.
                    value = trimWeight(unit.fromKg(weight)),
                    fontSize = 66.sp,
                    modifier = Modifier.weight(1f),
                    onTap = { typeWeight = true },
                    onStep = { steps ->
                        val next = (unit.fromKg(weight) + steps * step).coerceAtLeast(0.0)
                        weight = unit.toKg(next)
                        Haptics.tick(view)
                    },
                )
                InkText("×", style = InkType.NumberLarge.copy(fontSize = 30.sp), color = InkColors.BoneFaint)
                WorkingNumber(
                    label = "REPS",
                    value = reps.toString(),
                    fontSize = 66.sp,
                    modifier = Modifier.weight(1f),
                    onTap = { typeReps = true },
                    onStep = { steps ->
                        reps = (reps + steps).coerceIn(0, 99)
                        Haptics.tick(view)
                    },
                )
            }
            if (isPr) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    Stamp("印", tint = InkColors.Gold, filled = true, size = 40.dp, modifier = Modifier.goldShimmerOnce(key = "stagePr:${e1rm?.roundToInt()}"))
                    GoldBurst(trigger = e1rm?.roundToInt(), modifier = Modifier.matchParentSize())
                }
            }
        }

        // Under the dial, where the thumb already is: which unit this machine's
        // stack is marked in. Kilograms are still what gets stored either way,
        // so switching re-reads the same number — it never rewrites history.
        if (exercise != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InkSpacing.xs),
            ) {
                InkText("STACK", style = InkType.LabelSmall, color = InkColors.BoneFaint)
                WeightUnit.entries.forEach { option ->
                    InkChip(
                        text = option.label,
                        selected = unit == option,
                        onClick = {
                            if (unit != option) viewModel.setExerciseWeightUnit(exercise.name, option)
                        },
                    )
                }
            }
        }

        if (isPr) {
            InkText("personal record · e1RM ${trimWeight(e1rm ?: 0.0)}", style = InkType.Label, color = InkColors.Gold)
            Spacer(Modifier.height(InkSpacing.s))
        }

        SealButton(
            "Log set",
            hanzi = "印",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                viewModel.commitLoggedSet(item.id) { it.copy(weight = weight.takeIf { w -> w > 0 }, repsText = reps.toString()) }
                Haptics.success(view)
                onLogged(isPr)
            },
        )
        Spacer(Modifier.height(InkSpacing.m))
        SetLedger(item = item, unit = unit, viewModel = viewModel) { set ->
            set.weight?.let { weight = it }
            parseLeadingInt(set.repsText)?.let { reps = it }
        }
    }

    if (typeWeight) {
        NumberEntryDialog(
            title = "Exact weight (${unit.label.lowercase()})",
            initial = trimWeight(unit.fromKg(weight)),
            decimal = true,
            // The cap is on the typed reading, so 999 means 999 of whatever is
            // written on the stack — not 999 kg dressed up as 2,202 lb.
            onConfirm = { v -> weight = unit.toKg(v.coerceIn(0.0, 999.0)); typeWeight = false },
            onDismiss = { typeWeight = false },
        )
    }
    if (typeReps) {
        NumberEntryDialog(
            title = "Exact reps",
            initial = reps.toString(),
            decimal = false,
            onDismiss = { typeReps = false },
            onConfirm = { v -> reps = v.roundToInt().coerceIn(0, 99); typeReps = false },
        )
    }
}

private enum class HoldPhase { IDLE, READY, HOLD, SWITCH, HOLD_TWO }

private const val HOLD_GET_READY_SECONDS = 5

/** Seconds to change sides in a per-side pair: the bell and "switch sides",
 *  then a tick for each of the last five. Long enough to roll over from a
 *  side plank or swap legs in a half-split. */
private const val HOLD_SWITCH_SECONDS = 6

/** Bells ride STREAM_MUSIC — say so the moment a timed flow starts on a muted
 *  phone, while there's still a hand free to fix it. */
private fun warnIfBellsMuted(context: android.content.Context, viewModel: LiliangViewModel) {
    val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    if (audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
        viewModel.notice("Media volume is 0 — the bells will be silent")
    }
}

/**
 * Seconds this item prescribes as a timed hold — from an explicit
 * durationSeconds, a "20s" / "20s/side" / "30 sec" reps string (how older
 * plans wrote holds), or the exercise's seconds/minutes log format. Null for
 * an ordinary weight × reps prescription.
 */
private fun durationTargetSeconds(item: WorkoutSessionItem, exercise: ExerciseCatalogItem?): Int? {
    item.targetDurationSeconds?.let { return it }
    Regex("""^\s*(\d+)\s*s(ec(ond)?s?)?\b""", RegexOption.IGNORE_CASE)
        .find(item.targetReps)?.let { return it.groupValues[1].toInt() }
    if (exercise != null && exercise.logFormat != LogFormat.REPS) {
        val n = parseLeadingInt(item.targetReps) ?: return null
        return if (exercise.logFormat == LogFormat.MINUTES) n * 60 else n
    }
    return null
}

/**
 * Timed-hold stage — planks, hangs, anything measured in seconds rather than
 * reps. The dial sets the target; **Start hold** runs a five-second get-ready
 * count (a tick each second), one bell at go, the double bell when time is up
 * — so a scapular hang never needs eyes on the phone (Greg, 29 Aug 2026).
 * Stopping early keeps the honest number: the dial takes the elapsed seconds,
 * ready to log. Log set records a duration, not reps.
 */
@Composable
private fun DurationStage(
    item: WorkoutSessionItem,
    targetSeconds: Int?,
    perSide: Boolean,
    restChime: Boolean,
    viewModel: LiliangViewModel,
    modifier: Modifier = Modifier,
    onLogged: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val last = item.loggedSets.lastOrNull()
    var seconds by rememberSaveable(item.id, targetSeconds) {
        mutableIntStateOf(last?.durationSeconds ?: targetSeconds ?: 30)
    }
    var typeTime by rememberSaveable(item.id) { mutableStateOf(false) }
    // A per-side hold runs as a pair (R20): left, a switch count, right. One
    // row is logged for the pair, because one set means both sides. When the
    // second side stops short, the set keeps the weaker side's time and this
    // note keeps both.
    var sideNote by rememberSaveable(item.id) { mutableStateOf("") }

    // Hold-timer state. Wall-clock deadline (elapsedRealtime) so the count
    // survives recomposition and process death — the RoundTimer idiom.
    var phaseName by rememberSaveable(item.id) { mutableStateOf(HoldPhase.IDLE.name) }
    val phase = HoldPhase.valueOf(phaseName)
    var deadlineMs by rememberSaveable(item.id) { mutableLongStateOf(0L) }
    var remainingMs by remember(item.id) { mutableLongStateOf(0L) }
    var lastTickSecond by remember(item.id) { mutableIntStateOf(-1) }

    val bell = remember { WorkoutBell() }
    DisposableEffect(Unit) { onDispose { bell.release() } }
    // The switch is said aloud: he's on the floor, facing away from the phone.
    val voice = remember(perSide) { if (perSide) TtsEngine(context) else null }
    DisposableEffect(voice) { onDispose { voice?.shutdown() } }
    DisposableEffect(phase) {
        // Mid-hold he can't touch the phone — don't let the screen sleep, and
        // lock the pager so a stray fling can't dispose this clock (the flag
        // clears on dispose, so removal/close always unlocks).
        val active = phase != HoldPhase.IDLE
        view.keepScreenOn = active
        viewModel.setHoldActive(active)
        onDispose {
            view.keepScreenOn = false
            viewModel.setHoldActive(false)
        }
    }

    LaunchedEffect(phaseName, deadlineMs) {
        if (HoldPhase.valueOf(phaseName) == HoldPhase.IDLE) return@LaunchedEffect
        // Auto-log fires only when this loop WATCHED the hold run — a deadline
        // that lapsed while the process was dead must not log a phantom set.
        var sawRunning = false
        while (isActive) {
            val left = deadlineMs - SystemClock.elapsedRealtime()
            remainingMs = left.coerceAtLeast(0L)
            if (left <= 0L) {
                when (HoldPhase.valueOf(phaseName)) {
                    HoldPhase.READY -> {
                        bell.ringStart()
                        Haptics.confirm(view)
                        remainingMs = seconds * 1000L
                        deadlineMs = SystemClock.elapsedRealtime() + seconds * 1000L
                        phaseName = HoldPhase.HOLD.name
                    }
                    HoldPhase.HOLD -> if (perSide) {
                        // First side done: one bell, the word, a switch count.
                        // The double bell stays reserved for "both sides done".
                        bell.ringStart()
                        voice?.speak("Switch sides")
                        Haptics.confirm(view)
                        lastTickSecond = HOLD_SWITCH_SECONDS
                        remainingMs = HOLD_SWITCH_SECONDS * 1000L
                        deadlineMs = SystemClock.elapsedRealtime() + HOLD_SWITCH_SECONDS * 1000L
                        phaseName = HoldPhase.SWITCH.name
                    } else {
                        bell.ringEnd()
                        Haptics.success(view)
                        phaseName = HoldPhase.IDLE.name
                        // The double bell means the full target was held — that
                        // IS the seal. Log it so walking to the next station
                        // can't lose the set; early stops still log by hand.
                        if (sawRunning) {
                            viewModel.commitLoggedSet(item.id) { it.copy(durationSeconds = seconds) }
                            onLogged()
                        }
                    }
                    HoldPhase.SWITCH -> {
                        bell.ringStart()
                        Haptics.confirm(view)
                        remainingMs = seconds * 1000L
                        deadlineMs = SystemClock.elapsedRealtime() + seconds * 1000L
                        phaseName = HoldPhase.HOLD_TWO.name
                    }
                    HoldPhase.HOLD_TWO -> {
                        bell.ringEnd()
                        Haptics.success(view)
                        phaseName = HoldPhase.IDLE.name
                        // Both sides held in full: one row at the per-side time.
                        if (sawRunning) {
                            viewModel.commitLoggedSet(item.id) { it.copy(durationSeconds = seconds) }
                            onLogged()
                        }
                    }
                    else -> Unit
                }
                break
            }
            sawRunning = true
            val secLeft = ((left + 999) / 1000).toInt()
            val counting = HoldPhase.valueOf(phaseName).let { it == HoldPhase.READY || it == HoldPhase.SWITCH }
            if (counting && secLeft != lastTickSecond) {
                lastTickSecond = secLeft
                bell.tick()
            }
            kotlinx.coroutines.delay(120)
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // The dial must not move under a fixed deadline — hint pills only apply
        // while idle (the deadline math would lie mid-hold otherwise).
        HintRow(item.hints, onApplyDuration = { if (phase == HoldPhase.IDLE) seconds = it }) { _, _ -> }
        ExerciseRestBar(
            planSeconds = item.targetRestSeconds,
            lastLoggedAtMillis = item.loggedSets.mapNotNull { it.loggedAtMillis }.maxOrNull(),
            chime = restChime,
        )
        SetProgressLine(logged = item.loggedSets.size, planned = item.plannedSets)

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                when (phase) {
                    HoldPhase.IDLE -> {
                        WorkingNumber(
                            label = if (perSide) "HOLD · EACH SIDE" else "HOLD",
                            value = formatDuration(seconds),
                            fontSize = 56.sp,
                            onTap = { typeTime = true },
                            onStep = { steps ->
                                seconds = (seconds + steps * 5).coerceIn(5, 3600)
                                Haptics.tick(view)
                            },
                        )
                        SealButton(
                            "Start hold",
                            hanzi = "持",
                            tint = InkColors.Jade,
                            compact = true,
                            onClick = {
                                warnIfBellsMuted(context, viewModel)
                                lastTickSecond = -1
                                remainingMs = HOLD_GET_READY_SECONDS * 1000L
                                deadlineMs = SystemClock.elapsedRealtime() + HOLD_GET_READY_SECONDS * 1000L
                                phaseName = HoldPhase.READY.name
                            },
                        )
                    }
                    HoldPhase.READY -> {
                        InkText(if (perSide) "GET READY · LEFT SIDE FIRST" else "GET READY", style = InkType.Label, color = InkColors.Gold)
                        InkText(
                            "${((remainingMs + 999) / 1000).coerceAtLeast(1)}",
                            style = InkType.NumberHuge.copy(fontSize = 72.sp, lineHeight = 72.sp),
                            color = InkColors.Gold,
                        )
                        GhostButton(
                            "Stop",
                            modifier = Modifier.fillMaxWidth(0.6f).heightIn(min = 48.dp),
                            onClick = { phaseName = HoldPhase.IDLE.name },
                        )
                    }
                    HoldPhase.HOLD -> {
                        InkText(if (perSide) "LEFT · HOLD" else "HOLD", style = InkType.Label, color = InkColors.Vermillion)
                        InkText(
                            formatDuration(((remainingMs + 999) / 1000).toInt()),
                            style = InkType.NumberHuge.copy(fontSize = 72.sp, lineHeight = 72.sp),
                        )
                        InkText("of ${formatDuration(seconds)}", style = InkType.Label, color = InkColors.BoneFaint)
                        // The bail-out for a failing hold — a full-size target,
                        // not a chip, for a shaking hand.
                        GhostButton(
                            "Stop",
                            modifier = Modifier.fillMaxWidth(0.6f).heightIn(min = 48.dp),
                            onClick = {
                                val elapsed = ((seconds * 1000L - remainingMs) / 1000L).toInt()
                                if (elapsed in 1 until seconds) seconds = elapsed
                                phaseName = HoldPhase.IDLE.name
                            },
                        )
                    }
                    HoldPhase.SWITCH -> {
                        InkText("SWITCH SIDES", style = InkType.Label, color = InkColors.Gold)
                        InkText(
                            "${((remainingMs + 999) / 1000).coerceAtLeast(1)}",
                            style = InkType.NumberHuge.copy(fontSize = 72.sp, lineHeight = 72.sp),
                            color = InkColors.Gold,
                        )
                        InkText("right side next · ${formatDuration(seconds)}", style = InkType.Label, color = InkColors.BoneFaint)
                        GhostButton(
                            "Stop",
                            modifier = Modifier.fillMaxWidth(0.6f).heightIn(min = 48.dp),
                            onClick = {
                                sideNote = "right side not done"
                                phaseName = HoldPhase.IDLE.name
                            },
                        )
                    }
                    HoldPhase.HOLD_TWO -> {
                        InkText("RIGHT · HOLD", style = InkType.Label, color = InkColors.Vermillion)
                        InkText(
                            formatDuration(((remainingMs + 999) / 1000).toInt()),
                            style = InkType.NumberHuge.copy(fontSize = 72.sp, lineHeight = 72.sp),
                        )
                        InkText("of ${formatDuration(seconds)}", style = InkType.Label, color = InkColors.BoneFaint)
                        GhostButton(
                            "Stop",
                            modifier = Modifier.fillMaxWidth(0.6f).heightIn(min = 48.dp),
                            onClick = {
                                val elapsed = ((seconds * 1000L - remainingMs) / 1000L).toInt()
                                if (elapsed in 1 until seconds) {
                                    // The set records the weaker side; the note keeps both.
                                    sideNote = "left ${formatDuration(seconds)} · right ${formatDuration(elapsed)}"
                                    seconds = elapsed
                                }
                                phaseName = HoldPhase.IDLE.name
                            },
                        )
                    }
                }
            }
        }

        SealButton(
            "Log set",
            hanzi = "印",
            modifier = Modifier.fillMaxWidth(),
            enabled = phase == HoldPhase.IDLE,
            onClick = {
                val note = sideNote
                viewModel.commitLoggedSet(item.id) { it.copy(durationSeconds = seconds, comment = note) }
                sideNote = ""
                Haptics.success(view)
                onLogged()
            },
        )
        Spacer(Modifier.height(InkSpacing.m))
        SetLedger(item = item, viewModel = viewModel) { set ->
            set.durationSeconds?.let { seconds = it }
        }
    }

    if (typeTime) {
        DurationPickerDialog(
            title = "Hold time",
            totalSeconds = seconds,
            onDismiss = { typeTime = false },
            onConfirm = { s -> if (s != null && s > 0) seconds = s; typeTime = false },
        )
    }
}

@Composable
private fun RunningStage(item: WorkoutSessionItem, viewModel: LiliangViewModel, modifier: Modifier = Modifier, onLogged: () -> Unit) {
    val view = LocalView.current
    val last = item.loggedSets.lastOrNull()
    var distance by rememberSaveable(item.id) { mutableDoubleStateOf(last?.distanceKm ?: item.targetDistanceKm ?: 5.0) }
    var seconds by rememberSaveable(item.id) { mutableIntStateOf(last?.durationSeconds ?: item.targetDurationSeconds ?: 30 * 60) }
    var showTime by rememberSaveable(item.id) { mutableStateOf(false) }
    var typeDistance by rememberSaveable(item.id) { mutableStateOf(false) }
    val pace = if (distance > 0) (seconds / distance).roundToInt() else null

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                WorkingNumber(label = "KM", value = trimWeight(distance), fontSize = 66.sp, onTap = { typeDistance = true }, onStep = { s -> distance = (distance + s * 0.5).coerceAtLeast(0.0); Haptics.tick(view) })
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
                    InkPickerPill(label = "Time", value = formatDuration(seconds), onClick = { showTime = true })
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InkText("PACE", style = InkType.LabelSmall, color = InkColors.BoneFaint)
                        InkText(formatPace(pace?.toDouble()), style = InkType.NumberSmall, color = InkColors.BoneDim)
                    }
                }
            }
        }
        SealButton("Log run", hanzi = "跑", tint = InkColors.Jade, modifier = Modifier.fillMaxWidth(), onClick = {
            viewModel.commitLoggedSet(item.id) {
                it.copy(distanceKm = distance.takeIf { d -> d > 0 }, durationSeconds = seconds, paceSecondsPerKm = pace?.toDouble())
            }
            Haptics.success(view)
            onLogged()
        })
        Spacer(Modifier.height(InkSpacing.m))
        SetLedger(item = item, viewModel = viewModel) {}
    }
    if (showTime) {
        DurationPickerDialog(title = "Run time", totalSeconds = seconds, onDismiss = { showTime = false }, onConfirm = { s -> if (s != null) seconds = s; showTime = false })
    }
    if (typeDistance) {
        NumberEntryDialog(
            title = "Exact distance (km)",
            initial = trimWeight(distance),
            decimal = true,
            onDismiss = { typeDistance = false },
            onConfirm = { v -> distance = v.coerceIn(0.0, 500.0); typeDistance = false },
        )
    }
}

@Composable
private fun MartialStage(
    item: WorkoutSessionItem,
    exercise: ExerciseCatalogItem?,
    forms: List<MartialArtsForm>,
    combos: List<ComboItem>,
    viewModel: LiliangViewModel,
    modifier: Modifier = Modifier,
    onLogged: () -> Unit,
) {
    // Bag exercises plan rounds of combos; every other martial exercise is
    // form/practice work. The two never mix in one picker again.
    if (exercise?.supportsCombos() == true) {
        BagStage(item = item, combos = combos, viewModel = viewModel, modifier = modifier, onLogged = onLogged)
    } else {
        FormStage(item = item, forms = forms, viewModel = viewModel, modifier = modifier, onLogged = onLogged)
    }
}

/**
 * Heavy-bag stage: the round plan IS the interface — one combo per round
 * (pick from the library or build one on the spot), then "Let's go" runs the
 * timer and only the rounds actually completed land in the ledger.
 */
@Composable
private fun BagStage(
    item: WorkoutSessionItem,
    combos: List<ComboItem>,
    viewModel: LiliangViewModel,
    modifier: Modifier = Modifier,
    onLogged: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(item.id) { viewModel.ensureBagPlan(item, combos) }
    val plan = viewModel.bagPlans[item.id] ?: BagPlan()
    val rounds = plan.rounds

    // null = closed · -1 = append a round · >= 0 = replace that round
    var pickFor by rememberSaveable(item.id) { mutableStateOf<Int?>(null) }
    var buildFor by rememberSaveable(item.id) { mutableStateOf<Int?>(null) }
    var showWork by rememberSaveable(item.id) { mutableStateOf(false) }
    var showRest by rememberSaveable(item.id) { mutableStateOf(false) }

    fun placeCombo(index: Int, combo: ComboItem) {
        viewModel.updateBagPlan(item.id) { p ->
            if (index < 0 || index >= p.rounds.size) {
                p.copy(rounds = p.rounds + combo)
            } else {
                p.copy(rounds = p.rounds.toMutableList().also { it[index] = combo })
            }
        }
    }

    Column(modifier) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
        ) {
            InkText("THE ROUNDS", style = InkType.Label, color = InkColors.BoneFaint)
            if (rounds.isEmpty()) {
                InkText(
                    "Plan the session: one combo per round, then let's go.",
                    style = InkType.BodyDim,
                    color = InkColors.BoneFaint,
                )
            }
            rounds.forEachIndexed { i, combo ->
                RoundPlanRow(
                    index = i,
                    combo = combo,
                    onChange = { pickFor = i },
                    onRemove = {
                        viewModel.updateBagPlan(item.id) { p -> p.copy(rounds = p.rounds.toMutableList().also { it.removeAt(i) }) }
                        Haptics.tick(view)
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                GhostButton("+ Add round", onClick = { pickFor = -1 })
                if (rounds.isNotEmpty()) {
                    GhostButton("+ Same again", onClick = { placeCombo(-1, rounds.last()); Haptics.tick(view) })
                }
            }
            Spacer(Modifier.height(InkSpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l), verticalAlignment = Alignment.CenterVertically) {
                InkPickerPill(label = "Work", value = formatDuration(plan.workSeconds), onClick = { showWork = true })
                InkPickerPill(label = "Rest", value = formatDuration(plan.restSeconds), onClick = { showRest = true })
                InkChip(
                    text = "Call moves",
                    selected = plan.callMoves,
                    onClick = { viewModel.updateBagPlan(item.id) { it.copy(callMoves = !it.callMoves) } },
                )
            }
        }
        Spacer(Modifier.height(InkSpacing.s))
        SealButton(
            if (rounds.size == 1) "Let's go · 1 round" else "Let's go · ${rounds.size} rounds",
            hanzi = "武",
            enabled = rounds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                warnIfBellsMuted(context, viewModel)
                viewModel.startRoundTimer(
                    RoundTimerSetup(
                        rounds = rounds,
                        workSeconds = plan.workSeconds,
                        restSeconds = plan.restSeconds,
                        callMoves = plan.callMoves,
                        origin = RoundTimerOrigin.SessionItem(item.id),
                    ),
                )
            },
        )
        Spacer(Modifier.height(InkSpacing.xs))
        GhostButton(
            "Log plan without timer",
            enabled = rounds.isNotEmpty(),
            onClick = {
                viewModel.logBagRounds(item.id, rounds, rounds.size, plan.workSeconds)
                Haptics.success(view)
                onLogged()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(InkSpacing.m))
        SetLedger(item = item, viewModel = viewModel) {}
    }

    pickFor?.let { index ->
        ComboPickDialog(
            combos = combos,
            onDismiss = { pickFor = null },
            onPick = { combo -> placeCombo(index, combo); pickFor = null },
            onCreateNew = { buildFor = index; pickFor = null },
        )
    }
    buildFor?.let { index ->
        ComboBuilderDialog(
            suggestedDiscipline = item.exerciseName,
            onDismiss = { buildFor = null },
            onUse = { combo, save ->
                if (save) viewModel.saveComboToLibrary(combo)
                placeCombo(index, combo)
                buildFor = null
            },
        )
    }
    if (showWork) {
        DurationPickerDialog(title = "Work time", totalSeconds = plan.workSeconds, onDismiss = { showWork = false }, onConfirm = { s -> if (s != null) viewModel.updateBagPlan(item.id) { it.copy(workSeconds = s) }; showWork = false })
    }
    if (showRest) {
        DurationPickerDialog(title = "Rest between rounds", totalSeconds = plan.restSeconds, onDismiss = { showRest = false }, onConfirm = { s -> if (s != null) viewModel.updateBagPlan(item.id) { it.copy(restSeconds = s) }; showRest = false })
    }
}

/** One planned round: seal number, combo, its moves; tap to change, ✕ to drop. */
@Composable
private fun RoundPlanRow(index: Int, combo: ComboItem, onChange: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.chip)
            .border(1.dp, InkColors.Vermillion.copy(alpha = 0.35f), InkShapes.chip)
            .clickable(onClick = onChange)
            .padding(start = InkSpacing.m, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkText("${index + 1}", style = InkType.NumberSmall.copy(fontSize = 16.sp), color = InkColors.Vermillion)
        Spacer(Modifier.width(InkSpacing.m))
        Column(Modifier.weight(1f)) {
            InkText(combo.name, style = InkType.BodyStrong, maxLines = 1)
            val moves = combo.moves.joinToString(" · ") { it.displayName }
            if (moves.isNotBlank()) {
                InkText(moves, style = InkType.Label.copy(fontSize = 9.sp, letterSpacing = 0.2.sp), color = InkColors.BoneFaint, maxLines = 1)
            }
        }
        Box(
            Modifier.size(44.dp).clip(InkShapes.chip)
                .androidxSemantics("Remove round")
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { InkText("✕", style = InkType.Body, color = InkColors.BoneFaint) }
    }
}

/** Form/practice stage — Tai Chi and friends. Forms only; no combos here. */
@Composable
private fun FormStage(
    item: WorkoutSessionItem,
    forms: List<MartialArtsForm>,
    viewModel: LiliangViewModel,
    modifier: Modifier = Modifier,
    onLogged: () -> Unit,
) {
    val view = LocalView.current
    val last = item.loggedSets.lastOrNull()
    var formId by rememberSaveable(item.id) { mutableStateOf(last?.formId ?: "") }
    var moveIndex by rememberSaveable(item.id) { mutableIntStateOf(last?.formMoveIndex ?: 0) }
    var seconds by rememberSaveable(item.id) { mutableIntStateOf(last?.durationSeconds ?: item.targetDurationSeconds ?: 3 * 60) }
    var showPicker by rememberSaveable(item.id) { mutableStateOf(false) }
    var showMove by rememberSaveable(item.id) { mutableStateOf(false) }
    var showTime by rememberSaveable(item.id) { mutableStateOf(false) }

    val form = forms.firstOrNull { it.id == formId }
    val label = form?.displayShortTitle ?: "Choose a form"

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                Box(
                    Modifier.clip(InkShapes.chip).border(1.dp, InkColors.Vermillion.copy(alpha = 0.4f), InkShapes.chip).clickable { showPicker = true }.padding(horizontal = InkSpacing.l, vertical = InkSpacing.m),
                ) {
                    InkText(label, style = InkType.Title.copy(fontSize = 22.sp), color = if (form != null) InkColors.Bone else InkColors.BoneFaint, textAlign = TextAlign.Center)
                }
                if (form != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.l), verticalAlignment = Alignment.CenterVertically) {
                        if (form.moveCount > 0) {
                            InkPickerPill(label = "To move", value = moveIndex.takeIf { it > 0 }?.toString() ?: "—", onClick = { showMove = true })
                        }
                        InkPickerPill(label = "Time", value = formatDuration(seconds), onClick = { showTime = true })
                    }
                }
            }
        }
        SealButton("Log practice", hanzi = "武", enabled = form != null, modifier = Modifier.fillMaxWidth(), onClick = {
            viewModel.commitLoggedSet(item.id) {
                it.copy(
                    formId = formId,
                    formMoveIndex = moveIndex.takeIf { m -> m > 0 },
                    durationSeconds = seconds,
                )
            }
            Haptics.success(view)
            onLogged()
        })
        Spacer(Modifier.height(InkSpacing.m))
        SetLedger(item = item, viewModel = viewModel) {}
    }

    if (showPicker) {
        MartialArtsSelectionDialog(
            forms = forms,
            combos = emptyList(),
            title = "Choose a form",
            onDismiss = { showPicker = false },
            onPickForm = { f -> formId = f.id; moveIndex = viewModel.latestCheckpointMove(f.id) ?: f.moveCount; showPicker = false },
            onPickCombo = { },
        )
    }
    if (showMove && form != null && form.moveCount > 0) {
        FormMovePickerDialog(form = form, initialIndex = moveIndex.takeIf { it > 0 } ?: viewModel.latestCheckpointMove(form.id), onDismiss = { showMove = false }, onConfirm = { m -> moveIndex = m ?: 0; showMove = false })
    }
    if (showTime) {
        DurationPickerDialog(title = "Practice time", totalSeconds = seconds, onDismiss = { showTime = false }, onConfirm = { s -> if (s != null) seconds = s; showTime = false })
    }
}

// ───────────────────────── Stage pieces ─────────────────────────

/**
 * The log-set flourish: a vermillion 成 ("complete") seal stamps in over the
 * numbers with a spring overshoot, holds a beat, then shrinks and flies down
 * into the ledger and fades — quick (~750ms), never blocking. One-shot per
 * [tick]; instant-skip when motion is off.
 */
@Composable
private fun LogStampOverlay(tick: Int, gold: Boolean, modifier: Modifier = Modifier) {
    val p = remember { Animatable(1f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        p.snapTo(0f)
        p.animateTo(1f, tween(760, easing = androidx.compose.animation.core.LinearEasing))
    }
    if (p.value >= 1f) return
    BoxWithConstraints(modifier) {
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }
        val t = p.value
        // Phase 1 (stamp in): scale 0.3 → 1.18 → 1.0 at the numbers.
        // Phase 2 (fly): translate down to the ledger, scale → 0.34, fade out.
        val flyStart = 0.34f
        val scale = if (t < flyStart) {
            val s = t / flyStart
            if (s < 0.7f) 0.3f + (1.18f - 0.3f) * (s / 0.7f) else 1.18f + (1f - 1.18f) * ((s - 0.7f) / 0.3f)
        } else {
            val f = (t - flyStart) / (1f - flyStart)
            1f + (0.34f - 1f) * f
        }
        val fly = ((t - flyStart) / (1f - flyStart)).coerceIn(0f, 1f)
        val yFrac = 0.34f + (0.86f - 0.34f) * (fly * fly) // ease-in downward
        val alpha = if (t < 0.72f) 1f else (1f - (t - 0.72f) / 0.28f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    translationY = hPx * yFrac - this.size.height / 2f
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
        ) {
            // A PR seals in gold 印; an ordinary set in vermillion 成.
            Stamp(
                if (gold) "印" else "成",
                tint = if (gold) InkColors.Gold else InkColors.Vermillion,
                filled = true,
                size = 96.dp,
            )
        }
    }
}

/** The committed sets, as ink stamps. Tap one to pull its values up; ✕ removes. */
@Composable
private fun SetLedger(
    item: WorkoutSessionItem,
    viewModel: LiliangViewModel,
    unit: WeightUnit = WeightUnit.KG,
    onLoad: (WorkoutSessionSet) -> Unit,
) {
    // Sets still owed to the plan render as hollow rows below the logged ones —
    // the prescription is visible, but only pressed seals are real.
    val plannedRemaining = ((item.plannedSets ?: 0) - item.loggedSets.size).coerceAtLeast(0)
    var editingSet by remember(item.id) { mutableStateOf<WorkoutSessionSet?>(null) }
    if (item.loggedSets.isEmpty() && plannedRemaining == 0) {
        InkText("No sets yet — dial it in and press the seal.", style = InkType.BodyDim, color = InkColors.BoneFaint, modifier = Modifier.padding(bottom = InkSpacing.l))
        return
    }
    // ~3 rows at normal scale; grows with the font so 1.3× still shows rows.
    val ledgerHeight = (180 * LocalDensity.current.fontScale.coerceIn(1f, 1.35f)).dp
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(ledgerHeight),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.xs),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = InkSpacing.l),
    ) {
        items(item.loggedSets.size) { i ->
            val set = item.loggedSets[i]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.chip)
                    .border(1.dp, InkColors.Line, InkShapes.chip)
                    .clickable { onLoad(set) }
                    .padding(horizontal = InkSpacing.m, vertical = InkSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(InkShapes.seal).background(InkColors.Moss.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    InkText("${set.setIndex}", style = InkType.Caption, color = InkColors.Moss)
                }
                Spacer(Modifier.width(InkSpacing.m))
                // Bag rounds each carry their own combo — five identical
                // "3m 00s" rows told him nothing about which round was which.
                val summary = shortSetSummary(set.toRecord(), unit)
                    .let { base -> if (set.comboName.isNotBlank()) "$base · ${set.comboName}" else base }
                Column(Modifier.weight(1f)) {
                    InkText(summary, style = InkType.BodyStrong, maxLines = 1)
                    // His own words about this set (R20): tap to correct or delete.
                    if (set.comment.isNotBlank()) {
                        InkText(
                            "“${set.comment}”",
                            style = InkType.Label,
                            color = InkColors.BoneDim,
                            maxLines = 2,
                            modifier = Modifier.clickable { editingSet = set },
                        )
                    }
                }
                Box(
                    Modifier.size(44.dp).clip(InkShapes.chip)
                        .androidxSemantics("Remove set")
                        .clickable { viewModel.removeLoggedSet(item.id, set.id) },
                    contentAlignment = Alignment.Center,
                ) { InkText("✕", style = InkType.Body, color = InkColors.BoneFaint) }
            }
        }
        items(plannedRemaining) { k ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.chip)
                    .border(1.dp, InkColors.Line.copy(alpha = 0.6f), InkShapes.chip)
                    .padding(horizontal = InkSpacing.m, vertical = InkSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(InkShapes.seal).border(1.dp, InkColors.Line, InkShapes.seal), contentAlignment = Alignment.Center) {
                    InkText("${item.loggedSets.size + k + 1}", style = InkType.Caption, color = InkColors.BoneFaint)
                }
                Spacer(Modifier.width(InkSpacing.m))
                InkText(plannedSetSummary(item, unit), style = InkType.BodyStrong, color = InkColors.BoneDim, modifier = Modifier.weight(1f))
                InkText("planned", style = InkType.LabelSmall, color = InkColors.BoneFaint)
            }
        }
    }
    editingSet?.let { set ->
        NoteEditDialog(
            title = "Note · set ${set.setIndex}",
            initial = set.comment,
            onSave = { text ->
                viewModel.setLoggedSetNote(item.id, set.id, text)
                editingSet = null
            },
            onDelete = {
                viewModel.setLoggedSetNote(item.id, set.id, "")
                editingSet = null
            },
            onDismiss = { editingSet = null },
        )
    }
}

/** "57.5 kg × 8-12" — the prescription for a set that hasn't been done yet. */
private fun plannedSetSummary(item: WorkoutSessionItem, unit: WeightUnit = WeightUnit.KG): String {
    val parts = buildList {
        // The plan arrives in kilograms; show it in the unit the
        // machine is marked in, so the pin position and the prescription agree.
        item.targetWeight?.let { add("${trimWeight(unit.fromKg(it))} ${if (unit == WeightUnit.LB) "lb" else "kg"}") }
        item.targetReps.takeIf { it.isNotBlank() }?.let { add("× $it") }
        if (isEmpty()) {
            item.targetDurationSeconds?.let { add(formatDuration(it)) }
            item.targetDistanceKm?.let { add(formatDistance(it)) }
        }
    }
    return parts.joinToString(" ").ifBlank { "as planned" }
}

@Composable
private fun HintRow(
    hint: PerformanceHint,
    unit: WeightUnit = WeightUnit.KG,
    onApplyDuration: ((Int) -> Unit)? = null,
    onApply: (Double, Int) -> Unit,
) {
    if (hint.lastSessionSets.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = InkSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkText("LAST", style = InkType.LabelSmall, color = InkColors.BoneFaint)
        hint.lastSessionSets.take(6).forEach { set ->
            val w = set.weight
            val r = parseLeadingInt(set.repsText)
            val d = set.durationSeconds
            val tappable = (w != null && r != null) || (d != null && onApplyDuration != null)
            Box(
                Modifier.clip(InkShapes.chip).border(1.dp, InkColors.Vermillion.copy(alpha = 0.3f), InkShapes.chip)
                    .clickable(enabled = tappable) {
                        if (w != null && r != null) {
                            onApply(w, r)
                        } else if (d != null && onApplyDuration != null) {
                            onApplyDuration(d)
                        }
                    }
                    .padding(horizontal = InkSpacing.s, vertical = 4.dp),
            ) {
                InkText(shortSetSummary(set, unit), style = InkType.BodyDim, color = InkColors.BoneDim)
            }
        }
    }
}

/** Slim jade strip where the clock would be — this workout belongs to a later day. */
@Composable
private fun ScheduledBanner(date: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InkSpacing.l, vertical = InkSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkText("定", style = InkType.Seal.copy(fontSize = 15.sp), color = InkColors.Jade)
        InkText(
            "Scheduled · ${formatLongDate(date)} — logging opens on the day",
            style = InkType.Label,
            color = InkColors.Jade,
        )
    }
}

/**
 * Page 0 — the workout's title board. The name in brush, the day, the coach's
 * message for the whole session, then the running order: every exercise with
 * its prescription, tappable to jump straight to that page.
 */
@Composable
private fun WorkoutTitleStage(
    session: WorkoutSession,
    scheduledAhead: Boolean,
    exercises: List<ExerciseCatalogItem>,
    supersets: Map<Long, SupersetTag> = emptyMap(),
    onJumpToExercise: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = InkSpacing.l, vertical = InkSpacing.m),
    ) {
        InkText(formatLongDate(session.workoutDate), style = InkType.Label, color = if (scheduledAhead) InkColors.Jade else InkColors.BoneFaint)
        Spacer(Modifier.height(2.dp))
        InkText(session.name.ifBlank { "Workout" }, style = InkType.Masthead, maxLines = 3)
        Spacer(Modifier.height(2.dp))
        InkText(
            pluralize(session.items.size, "exercise") + if (scheduledAhead) " · scheduled" else "",
            style = InkType.Label,
            color = InkColors.BoneFaint,
        )
        if (supersets.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            InkText("Same letter, same colour: a superset — alternate them", style = InkType.Label, color = InkColors.BoneFaint)
        }
        if (session.notes.isNotBlank()) {
            Spacer(Modifier.height(InkSpacing.m))
            CoachNote(session.notes)
        }
        Spacer(Modifier.height(InkSpacing.m))
        BrushDivider(seed = session.name.hashCode())
        Spacer(Modifier.height(InkSpacing.s))
        session.items.forEachIndexed { i, item ->
            val tag = supersets[item.id]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(InkShapes.chip)
                    .clickable { onJumpToExercise(i) }
                    .padding(horizontal = InkSpacing.s, vertical = InkSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A superset's rows share a coloured edge and a lettered seal,
                // so which exercises go together is visible at a glance (R20).
                if (tag != null) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(InkShapes.chip).background(InkColors.superset(tag.index)))
                    Spacer(Modifier.width(6.dp))
                } else {
                    Spacer(Modifier.width(9.dp))
                }
                InkText(
                    "${i + 1}",
                    style = InkType.NumberSmall.copy(fontSize = 14.sp),
                    color = if (item.loggedSets.isNotEmpty()) InkColors.Moss else InkColors.BoneFaint,
                    modifier = Modifier.width(26.dp),
                )
                if (tag != null) {
                    SupersetBadge(tag, size = 20.dp)
                    Spacer(Modifier.width(InkSpacing.s))
                }
                Column(Modifier.weight(1f)) {
                    InkText(item.title.ifBlank { "Exercise" }, style = InkType.BodyStrong, maxLines = 1)
                    val plan = plannedItemSummary(
                        item,
                        exercises.firstOrNull { it.name == item.exerciseName }?.weightUnit ?: WeightUnit.KG,
                    )
                    if (plan.isNotBlank()) {
                        InkText(plan, style = InkType.Label, color = InkColors.BoneFaint, maxLines = 1)
                    }
                }
                if (item.loggedSets.isNotEmpty()) {
                    InkText("${item.loggedSets.size}", style = InkType.NumberSmall.copy(fontSize = 13.sp), color = InkColors.Moss)
                    Spacer(Modifier.width(4.dp))
                    InkText("成", style = InkType.Seal.copy(fontSize = 14.sp), color = InkColors.Moss)
                }
            }
        }
    }
}

/** "3 × 8-12 @ 57.5 kg · rest 2m" — one line of prescription for the running order. */
private fun plannedItemSummary(item: WorkoutSessionItem, unit: WeightUnit = WeightUnit.KG): String {
    val core = buildList {
        item.plannedSets?.let { sets ->
            val reps = item.targetReps.takeIf { it.isNotBlank() }
            val hold = item.targetDurationSeconds?.let { formatDuration(it) }
            add(
                when {
                    reps != null -> "$sets × $reps"
                    hold != null -> "$sets × $hold"
                    else -> "$sets sets"
                },
            )
        }
        item.targetWeight?.let { add("@ ${trimWeight(unit.fromKg(it))} ${if (unit == WeightUnit.LB) "lb" else "kg"}") }
        if (item.plannedSets == null && item.targetWeight == null) {
            item.targetDurationSeconds?.let { add(formatDuration(it)) }
            item.targetDistanceKm?.let { add(formatDistance(it)) }
            if (item.comboName.isNotBlank()) add(item.comboName)
        }
    }
    val rest = item.targetRestSeconds?.let { "rest ${formatDuration(it)}" }
    return (core + listOfNotNull(rest)).joinToString(" · ")
}

/** A superset's seal: its letter on its colour. */
@Composable
private fun SupersetBadge(tag: SupersetTag, size: Dp = 20.dp) {
    Box(
        Modifier
            .size(size)
            .clip(InkShapes.chip)
            .background(InkColors.superset(tag.index))
            .androidxSemantics("Superset ${tag.letter}"),
        contentAlignment = Alignment.Center,
    ) {
        InkText(
            tag.letter.toString(),
            style = InkType.Label.copy(fontSize = (size.value * 0.62f).sp, lineHeight = (size.value * 0.7f).sp, fontWeight = FontWeight.Bold),
            color = InkColors.Paper,
        )
    }
}

/** "set 3" while that set is still to come; "after set 3" once the plan is done. */
private fun noteTarget(item: WorkoutSessionItem): String {
    val logged = item.loggedSets.size
    val planned = item.plannedSets
    return if (planned != null && logged >= planned && logged > 0) "after set $logged" else "set ${logged + 1}"
}

/** The note waiting for the set he's on — it moves onto that set when he logs it. Tap to edit. */
@Composable
private fun PendingNoteLine(item: WorkoutSessionItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.chip)
            .border(1.dp, InkColors.Vermillion.copy(alpha = 0.35f), InkShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = InkSpacing.m, vertical = InkSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkText("记", style = InkType.Seal.copy(fontSize = 16.sp), color = InkColors.Vermillion)
        InkText("${noteTarget(item).uppercase()} · ${item.pendingComment}", style = InkType.BodyDim, color = InkColors.BoneDim, maxLines = 2)
    }
}

/**
 * The listening sheet: it starts hearing as it opens, shows the words as they
 * come, and saves the note the moment the phone's recogniser finishes. Done
 * stops early; a failure offers another go or the keyboard. Nothing is
 * recorded: the words are the note.
 */
@Composable
private fun VoiceNoteSheet(
    target: String,
    onHeard: (String) -> Unit,
    onType: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val recognizer = remember { VoiceNoteRecognizer(context) }
    var heard by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            recognizer.release()
            view.keepScreenOn = false
        }
    }
    LaunchedEffect(attempt) {
        heard = ""
        problem = null
        if (!recognizer.isAvailable()) {
            problem = "No speech recogniser on this phone"
            return@LaunchedEffect
        }
        recognizer.start(
            onPartial = { heard = it },
            onFinal = { text -> onHeard(text) },
            // Words already heard are worth more than a clean error.
            onError = { message -> if (heard.isNotBlank()) onHeard(heard) else problem = message },
        )
    }
    val trouble = problem
    InkDialog(title = "Note · $target", onDismiss = onDismiss, scrollable = false) {
        InkText(
            trouble ?: if (heard.isBlank()) "Listening — say it" else "Listening…",
            style = InkType.Label,
            color = if (trouble != null) InkColors.Vermillion else InkColors.Gold,
        )
        InkText(
            heard.ifBlank { "…" },
            style = InkType.BodyStrong,
            color = if (heard.isBlank()) InkColors.BoneFaint else InkColors.Bone,
        )
        if (trouble == null) {
            SealButton("Done", hanzi = "记", modifier = Modifier.fillMaxWidth(), onClick = { recognizer.stop() })
        } else {
            SealButton("Try again", hanzi = "记", modifier = Modifier.fillMaxWidth(), onClick = { attempt++ })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            GhostButton("Type instead", onClick = onType)
            GhostButton("Cancel", onClick = onDismiss)
        }
    }
}

/** Write, correct or delete a note by hand. */
@Composable
private fun NoteEditDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    InkDialog(title = title, onDismiss = onDismiss) {
        InkTextField(label = "Note", value = text, singleLine = false, minLines = 3, onValueChange = { text = it })
        SealButton("Save", hanzi = "记", modifier = Modifier.fillMaxWidth(), enabled = text.isNotBlank(), onClick = { onSave(text) })
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            if (onDelete != null) GhostButton("Delete note", onClick = onDelete)
            GhostButton("Cancel", onClick = onDismiss)
        }
    }
}

@Composable
private fun CoachNote(note: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(InkShapes.chip).background(InkColors.InkWash.copy(alpha = 0.10f)).padding(horizontal = InkSpacing.m, vertical = InkSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        InkText("师", style = InkType.Seal.copy(fontSize = 16.sp), color = InkColors.InkWash)
        InkText(note, style = InkType.BodyDim, color = InkColors.BoneDim)
    }
}

@Composable
private fun AddExercisePage(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(InkSpacing.l), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(InkShapes.card).border(1.dp, InkColors.Line, InkShapes.card).clickable(onClick = onAdd).padding(vertical = InkSpacing.hero),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
        ) {
            InkText("+", style = InkType.NumberLarge, color = InkColors.BoneDim)
            InkText("Add exercise", style = InkType.Heading, color = InkColors.BoneDim)
        }
    }
}

@Composable
private fun SessionDetailsDialog(
    session: WorkoutSession,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onUpdate: (WorkoutSession) -> Unit,
) {
    InkDialog(title = "Workout details", onDismiss = onDismiss) {
        if (session.sourceTemplateId == null) {
            InkTextField(label = "Name", value = session.name, onValueChange = onNameChange)
        } else {
            InkText(session.name.ifBlank { "Workout" }, style = InkType.Title)
        }
        InkText(formatLongDate(session.workoutDate), style = InkType.BodyDim, color = InkColors.BoneDim)
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            InkDecimalField(key = "bw:${session.id}", label = "Bodyweight", value = editableDouble(session.bodyWeightKg), modifier = Modifier.weight(1f), onValueChange = { raw -> onUpdate(session.copy(bodyWeightKg = parseLeadingDouble(raw))) })
            InkTextField(label = "Mood", value = session.mood, modifier = Modifier.weight(1f), onValueChange = { onUpdate(session.copy(mood = it)) })
        }
        InkTextField(label = "Diet", value = session.diet, onValueChange = { onUpdate(session.copy(diet = it)) })
        GhostButton("Done", onClick = onDismiss)
    }
}

/** The finish seal-press: a vermillion blot blooms full-screen, 印 stamps, then returns. */
@Composable
private fun SealPressOverlay(onComplete: () -> Unit) {
    val bloom = remember { Animatable(0f) }
    val view = LocalView.current
    var done by remember { mutableStateOf(false) }
    val finish = {
        if (!done) {
            done = true
            onComplete()
        }
    }
    LaunchedEffect(Unit) {
        Haptics.success(view)
        bloom.animateTo(1f, tween(620))
        kotlinx.coroutines.delay(260)
        finish()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { finish() } }
            .drawBehind {
                val t = bloom.value
                val maxR = max(size.width, size.height) * 1.1f
                drawCircle(InkColors.Vermillion.copy(alpha = 0.96f * t.coerceAtMost(1f)), radius = maxR * t, center = Offset(size.width / 2f, size.height / 2f))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bloom.value > 0.55f) Stamp("印", filled = false, tint = InkColors.Bone, size = 96.dp)
    }
}

@Composable
private fun NewExerciseDialog(
    initialName: String,
    muscles: List<String>,
    categories: List<String>,
    onDismiss: () -> Unit,
    onJustAdd: () -> Unit,
    onSave: (ExerciseCatalogItem) -> Unit,
) {
    // One saveable draft instead of six parallel fields — survives rotation and
    // renders through the same ExerciseFields as the library editor (F7).
    var draft by rememberSaveable(initialName, stateSaver = ExerciseDraftSaver) {
        mutableStateOf(ExerciseCatalogItem(name = initialName, defaultMuscle = "", defaultCategory = "Hypertrophy", logFormat = LogFormat.REPS, isBodyweight = false))
    }
    InkDialog(title = "New exercise", onDismiss = onDismiss) {
        InkText("“$initialName” isn't in your library. Save it so it earns history, hints and a coach.", style = InkType.BodyDim, color = InkColors.BoneDim)
        ExerciseFields(item = draft, muscles = muscles, categories = categories, onChange = { draft = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            GhostButton("Just once", onClick = onJustAdd)
            Spacer(Modifier.weight(1f))
            GhostButton("Cancel", onClick = onDismiss)
            SealButton("Save", enabled = draft.name.isNotBlank() && draft.defaultMuscle.isNotBlank(), compact = true, onClick = {
                onSave(draft.copy(name = draft.name.trim()))
            })
        }
    }
}

/** Saver for the dialog's exercise draft (ExerciseCatalogItem isn't Parcelable). */
private val ExerciseDraftSaver = androidx.compose.runtime.saveable.listSaver<ExerciseCatalogItem, String>(
    save = { listOf(it.name, it.defaultMuscle, it.defaultCategory, if (it.isBodyweight) "1" else "0", it.logFormat.dbValue, it.movementType.name) },
    restore = {
        ExerciseCatalogItem(
            name = it[0], defaultMuscle = it[1], defaultCategory = it[2], isBodyweight = it[3] == "1",
            logFormat = LogFormat.fromDb(it[4]),
            movementType = MovementType.entries.firstOrNull { m -> m.name == it[5] } ?: MovementType.AUTO,
        )
    },
)


private fun WorkoutSessionSet.toRecord() = com.liliang.app.data.WorkoutSetRecord(
    weight = weight, repsText = repsText, durationSeconds = durationSeconds, distanceKm = distanceKm, paceSecondsPerKm = paceSecondsPerKm,
)

