package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.CoachPreviewItem
import com.liliang.app.data.DayWorkoutSnapshot
import com.liliang.app.data.PlanDayType
import com.liliang.app.data.WorkoutGroup
import com.liliang.app.data.WorkoutTemplate
import com.liliang.app.data.formatDuration
import com.liliang.app.data.formatWeight
import com.liliang.app.data.parseLeadingInt
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.goldShimmerOnce
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton
import com.liliang.app.design.Stamp
import java.time.LocalDate

/**
 * The day sheet reached by tapping a calendar cell. The date is the
 * headline; below it, the plan (tap to open, long-press to clear), any
 * unfinished draft, quick-log seals, the coach's plan as margin notes, and
 * the completed ledgers with save-as-template.
 */
@Composable
fun DayDetailScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val ledger = com.liliang.app.design.rememberRevealLedger()
    val snapshot = viewModel.selectedDaySnapshot
    val date = snapshot.date.ifBlank { viewModel.selectedTodayDate }
    val coachEnabled = viewModel.configSnapshot.coachEnabled
    val canEdit = viewModel.canEditScheduleForSelectedDate()
    val isFuture = remember(date) { runCatching { LocalDate.parse(date) }.getOrNull()?.isAfter(LocalDate.now()) == true }
    val canQuickLog = remember(date) { runCatching { LocalDate.parse(date) }.getOrNull()?.let { !it.isAfter(LocalDate.now()) } ?: false }

    var showChooser by rememberSaveable { mutableStateOf(false) }
    var pendingClearPlan by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteDraft by rememberSaveable { mutableStateOf(false) }
    // Index into completedWorkouts; -1 = nothing pending.
    var pendingDeleteWorkout by rememberSaveable { mutableIntStateOf(-1) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
    ) {
        item("top") {
            EditorHeader(
                formatLongDate(date),
                subtitle = stateLabel(snapshot, date),
                subtitleColor = stateColor(snapshot),
                onBack = onBack,
            )
        }

        // Scheduled workout — tap to open/start, long-press to clear.
        val recommendation = snapshot.recommendation
        if (recommendation != null && recommendation.dayType != PlanDayType.REST) {
            item("plan") {
                val label = when (recommendation.dayType) {
                    PlanDayType.TEMPLATE -> recommendation.templateName
                    PlanDayType.CUSTOM -> recommendation.customWorkoutName.ifBlank { recommendation.dayLabel }
                    PlanDayType.REST -> "Rest"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InkShapes.card)
                        .border(1.dp, InkColors.Vermillion.copy(alpha = 0.6f), InkShapes.card)
                        .pointerInput(date, canEdit) {
                            detectTapGestures(
                                onTap = { viewModel.startRecommendedWorkout() },
                                onLongPress = { if (canEdit) { com.liliang.app.platform.Haptics.confirm(view); pendingClearPlan = true } },
                            )
                        }
                        .padding(InkSpacing.l),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (recommendation.planName.isNotBlank()) {
                                InkText(recommendation.planName.uppercase(), style = InkType.Label, color = InkColors.Vermillion)
                            }
                            InkText(label, style = InkType.Title, maxLines = 2)
                        }
                        InkText(if (isFuture) "open →" else "start →", style = InkType.Label, color = InkColors.Vermillion)
                    }
                    if (canEdit) {
                        InkText("hold · clear from plan", style = InkType.Caption, color = InkColors.BoneFaint)
                    }
                }
            }
        } else if (recommendation?.dayType == PlanDayType.REST) {
            item("rest") {
                InkText("Rest day — recovery is training too.", style = InkType.Body, color = InkColors.BoneDim)
            }
        }

        // Draft — tap to resume, long-press to delete.
        snapshot.draftSession?.let { draft ->
            item("draft") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InkShapes.card)
                        .border(1.dp, InkColors.Gold.copy(alpha = 0.7f), InkShapes.card)
                        .pointerInput(draft.id) {
                            detectTapGestures(
                                onTap = { viewModel.openDraftSession(draft.id) },
                                onLongPress = { com.liliang.app.platform.Haptics.confirm(view); pendingDeleteDraft = true },
                            )
                        }
                        .padding(InkSpacing.l),
                ) {
                    val scheduledAhead = date > java.time.LocalDate.now().toString()
                    // Zero logged sets = never started: on its day that's a
                    // scheduled workout ready to begin, not one he abandoned.
                    val pristine = draft.items.none { it.loggedSets.isNotEmpty() }
                    InkText(
                        if (scheduledAhead || pristine) "SCHEDULED" else "UNFINISHED",
                        style = InkType.Label,
                        color = if (scheduledAhead || pristine) InkColors.Jade else InkColors.Gold,
                    )
                    InkText(draft.name.ifBlank { "Custom workout" }, style = InkType.Title, maxLines = 2)
                    InkText(
                        "${pluralize(draft.items.size, "exercise")} · ${
                            when {
                                scheduledAhead -> "tap to preview"
                                pristine -> "tap to begin"
                                else -> "tap to resume"
                            }
                        } · hold to delete",
                        style = InkType.BodyDim,
                        color = InkColors.BoneDim,
                    )
                    if (snapshot.extraDraftCount > 0) {
                        InkText(
                            "+${snapshot.extraDraftCount} more draft${if (snapshot.extraDraftCount == 1) "" else "s"} parked on this day — resume or delete this one to reach it",
                            style = InkType.LabelSmall,
                            color = InkColors.BoneFaint,
                        )
                    }
                }
            }
        }

        // Actions
        item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                // Past days can backfill a full workout too — history belongs
                // to the person who trained it, not the calendar.
                if ((canEdit || canQuickLog) && (recommendation == null || recommendation.dayType == PlanDayType.REST)) {
                    SealButton("Add workout", onClick = { showChooser = true }, compact = true)
                }
                if (canQuickLog) {
                    GhostButton("跑 Run", onClick = {
                        viewModel.prepareRunQuickLog()
                        viewModel.pushOverlay(com.liliang.app.Overlay.RunLog(date))
                    })
                    GhostButton("武 Martial", onClick = {
                        viewModel.refreshMartialArtsForms()
                        viewModel.pushOverlay(com.liliang.app.Overlay.MartialLog(date))
                    })
                }
            }
        }

        // Coach's plan
        val coachPreview = viewModel.dayCoachPreview?.takeIf { coachEnabled }
        if (!coachPreview.isNullOrEmpty()) {
            item("coach") { CoachPlan(coachPreview) }
        }

        // The day's training record — sealed entries that soak in.
        if (snapshot.completedWorkouts.isNotEmpty()) {
            item("rec-label") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    InkText("录", style = com.liliang.app.design.BrushHanzi(20.sp), color = InkColors.Gold)
                    InkText("TRAINING RECORD", style = InkType.Label, color = InkColors.Gold)
                }
            }
            items(snapshot.completedWorkouts.size) { i ->
                val workout = snapshot.completedWorkouts[i]
                CompletedWorkoutBlock(
                    workout = workout,
                    index = i,
                    ledger = ledger,
                    onSaveAsTemplate = { viewModel.saveWorkoutGroupAsTemplate(workout) },
                    onRequestDelete = {
                        com.liliang.app.platform.Haptics.confirm(view)
                        pendingDeleteWorkout = i
                    },
                )
            }
        }
    }

    if (showChooser) {
        StartWorkoutDialog(
            title = if (isFuture) "Schedule workout" else "Start workout",
            recentWorkouts = viewModel.plannerSnapshot.recentWorkouts,
            templates = viewModel.plannerSnapshot.templates,
            onDismiss = { showChooser = false },
            onBlank = {
                showChooser = false
                viewModel.startCustomWorkout(date)
            },
            onRecent = { workout ->
                showChooser = false
                viewModel.startWorkoutFromRecent(workout, date)
            },
            onTemplate = { template ->
                showChooser = false
                viewModel.startCustomWorkoutFromTemplate(template.id, date)
            },
        )
    }
    if (pendingClearPlan) {
        ConfirmationDialog(
            title = "Remove from plan?",
            message = "Turns this day into a rest day in the plan. History isn't touched.",
            confirmLabel = "Remove",
            onDismiss = { pendingClearPlan = false },
            onConfirm = {
                viewModel.clearSelectedDatePlanSlot()
                pendingClearPlan = false
            },
        )
    }
    if (pendingDeleteDraft) {
        ConfirmationDialog(
            title = "Delete draft?",
            message = "Deletes the unfinished workout and its logged sets for this day.",
            confirmLabel = "Delete",
            onDismiss = { pendingDeleteDraft = false },
            onConfirm = {
                viewModel.deleteSelectedDateDraft()
                pendingDeleteDraft = false
            },
        )
    }
    snapshot.completedWorkouts.getOrNull(pendingDeleteWorkout)?.let { workout ->
        ConfirmationDialog(
            title = "Delete from history?",
            message = "Removes “${workout.displayName}” and every set it logged — records, charts and PRs will forget it. This cannot be undone.",
            confirmLabel = "Delete forever",
            onDismiss = { pendingDeleteWorkout = -1 },
            onConfirm = {
                viewModel.deleteCompletedWorkout(workout)
                pendingDeleteWorkout = -1
            },
        )
    }
}

@Composable
private fun CoachPlan(items: List<CoachPreviewItem>) {
    // The margin rule is drawn behind at the column's REAL height — the old
    // 44dp-per-row guess under/overshot on wrapped rows and large font scale.
    Row(
        modifier = Modifier.drawBehind {
            drawRoundRect(
                color = InkColors.InkWash.copy(alpha = 0.8f),
                topLeft = androidx.compose.ui.geometry.Offset.Zero,
                size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
            )
        },
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        Spacer(Modifier.width(3.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            InkText("COACH'S PLAN", style = InkType.Label, color = InkColors.Mist)
            items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InkText(item.title, style = InkType.Body, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(InkSpacing.m))
                    InkText(item.prescription, style = InkType.NumberSmall.copy(fontSize = 14.sp), color = InkColors.Vermillion, maxLines = 1)
                }
                if (item.note.isNotBlank()) {
                    InkText(item.note, style = InkType.Label.copy(letterSpacing = 0.4.sp), color = InkColors.BoneFaint)
                }
            }
        }
    }
}

private fun WorkoutGroup.totalVolume(): Double =
    entries.sumOf { entry -> entry.sets.sumOf { (it.weight ?: 0.0) * (parseLeadingInt(it.repsText) ?: 0) } }

/**
 * One sealed training record — a ledger entry on paper with a gold 成 seal, a
 * cloud-collar rule, and each exercise's sets recorded as a wrapped row of
 * pills. The whole entry soaks in, staggered by its position.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompletedWorkoutBlock(
    workout: WorkoutGroup,
    index: Int,
    ledger: com.liliang.app.design.RevealLedger,
    onSaveAsTemplate: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val totalSets = workout.entries.sumOf { it.sets.size }
    val totalVolume = workout.totalVolume()
    val totalSeconds = workout.entries.sumOf { entry -> entry.sets.sumOf { it.durationSeconds ?: 0 } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .inkBleedIn(key = "rec:${workout.date}:$index", ledger = ledger, delayMillis = index * 90)
            .clip(InkShapes.card)
            .background(InkColors.PaperRaised)
            .border(1.dp, InkColors.Gold.copy(alpha = 0.4f), InkShapes.card)
            // Hold to erase from history — no hint text by design; the same
            // hidden-but-consistent gesture as drafts, then a hard confirm.
            .pointerInput(workout.date, workout.workoutName) {
                detectTapGestures(onLongPress = { onRequestDelete() })
            }
            .drawBehind {
                // a gold ledger rule down the left margin
                drawRect(
                    color = InkColors.Gold.copy(alpha = 0.6f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                )
            }
            .padding(InkSpacing.l),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                InkText(workout.displayName, style = InkType.Title.copy(fontSize = 22.sp), maxLines = 2)
                InkText(
                    buildString {
                        append("${pluralize(workout.entries.size, "exercise")} · ${pluralize(totalSets, "set")}")
                        if (totalVolume > 0) append(" · ${formatWeight(totalVolume)} moved")
                        if (totalSeconds > 60) append(" · ${formatDuration(totalSeconds)}")
                        workout.durationSeconds?.let { if (it >= 60) append(" · ${formatDuration(it)} in the gym") }
                    },
                    style = InkType.Label,
                    color = InkColors.BoneFaint,
                )
            }
            Stamp(
                "成", tint = InkColors.Gold, filled = true, size = 44.dp,
                modifier = Modifier.goldShimmerOnce(key = "recseal:${workout.date}:$index", ledger = ledger),
            )
        }
        Spacer(Modifier.height(InkSpacing.m))
        BrushDivider(seed = 96)
        Spacer(Modifier.height(InkSpacing.m))
        workout.entries.forEachIndexed { ei, entry ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("${ei + 1}", style = InkType.NumberSmall.copy(fontSize = 14.sp), color = InkColors.Vermillion, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    InkText(entry.title, style = InkType.Heading.copy(fontSize = 16.sp), maxLines = 1)
                    Spacer(Modifier.height(InkSpacing.xs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(InkSpacing.xs), verticalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
                        entry.sets.forEach { set ->
                            Box(
                                Modifier
                                    .clip(InkShapes.chip)
                                    .border(1.dp, InkColors.Line, InkShapes.chip)
                                    .padding(horizontal = InkSpacing.s, vertical = 3.dp),
                            ) {
                                InkText(shortSetSummary(set), style = InkType.Label.copy(fontSize = 11.sp), color = InkColors.BoneDim)
                            }
                        }
                    }
                    averageObservedRestSeconds(entry.sets)?.let { avg ->
                        Spacer(Modifier.height(2.dp))
                        InkText("rest ~${avg}s between sets", style = InkType.LabelSmall, color = InkColors.BoneFaint)
                    }
                }
            }
            if (ei != workout.entries.lastIndex) {
                Spacer(Modifier.height(InkSpacing.s))
                BrushDivider(seed = entry.title.hashCode(), color = InkColors.Line)
                Spacer(Modifier.height(InkSpacing.s))
            }
        }
        Spacer(Modifier.height(InkSpacing.m))
        GhostButton("Save as workout", onClick = onSaveAsTemplate)
    }
}

private fun stateLabel(snapshot: DayWorkoutSnapshot, date: String): String {
    val isToday = date == LocalDate.now().toString()
    return when {
        snapshot.completedWorkouts.isNotEmpty() -> "Trained"
        snapshot.draftSession != null -> "Draft saved"
        snapshot.recommendation?.dayType == PlanDayType.REST -> if (isToday) "Rest day" else "Rest"
        snapshot.recommendation != null -> if (isToday) "Scheduled today" else "Scheduled"
        else -> if (isToday) "Nothing scheduled" else "No workout"
    }
}

private fun stateColor(snapshot: DayWorkoutSnapshot): Color = when {
    snapshot.completedWorkouts.isNotEmpty() -> InkColors.Gold
    snapshot.draftSession != null -> InkColors.Gold
    snapshot.recommendation?.dayType == PlanDayType.REST -> InkColors.BoneFaint
    snapshot.recommendation != null -> InkColors.Vermillion
    else -> InkColors.BoneFaint
}

/**
 * The chooser for starting/scheduling a workout: blank, one of the recent
 * workouts (deduped, engine re-prescribes), or a saved template.
 */
@Composable
fun StartWorkoutDialog(
    title: String,
    recentWorkouts: List<WorkoutGroup>,
    templates: List<WorkoutTemplate>,
    onDismiss: () -> Unit,
    onBlank: () -> Unit,
    onRecent: (WorkoutGroup) -> Unit,
    onTemplate: (WorkoutTemplate) -> Unit,
) {
    val recents = remember(recentWorkouts) { recentWorkouts.distinctBy { it.displayName.lowercase() }.take(6) }
    InkDialog(title = title, onDismiss = onDismiss) {
        SealButton("Blank workout", hanzi = "练", onClick = onBlank, modifier = Modifier.fillMaxWidth())
        if (recents.isNotEmpty()) {
            InkText("RECENT", style = InkType.Label, color = InkColors.BoneFaint)
            recents.forEach { workout ->
                ChooserRow(
                    title = workout.displayName,
                    subtitle = "${pluralize(workout.entries.size, "exercise")} · ${formatShortDate(workout.date)}",
                    onClick = { onRecent(workout) },
                )
            }
        }
        if (templates.isNotEmpty()) {
            InkText("SAVED WORKOUTS", style = InkType.Label, color = InkColors.BoneFaint)
            templates.forEach { template ->
                ChooserRow(
                    title = template.name,
                    subtitle = "${template.items.size} items",
                    onClick = { onTemplate(template) },
                )
            }
        }
        GhostButton("Cancel", onClick = onDismiss)
    }
}

@Composable
private fun ChooserRow(title: String, subtitle: String, onClick: () -> Unit) {
    InkListRow(title, subtitle = subtitle, onClick = onClick)
}

/**
 * Mean gap between consecutive logged sets of one exercise — the observed
 * rest (each gap includes the following set's own execution, so read it as
 * an upper bound). Gaps outside 5s–30m are transitions/interruptions, not
 * rest, and are dropped. Null when timestamps are missing (pre-R5 history).
 */
private fun averageObservedRestSeconds(sets: List<com.liliang.app.data.WorkoutSetRecord>): Int? {
    val times = sets.mapNotNull { it.loggedAtMillis }.sorted()
    if (times.size < 2) return null
    val gaps = times.zipWithNext { a, b -> (b - a) / 1000L }.filter { it in 5..1800 }
    if (gaps.isEmpty()) return null
    return (gaps.average()).toInt()
}
