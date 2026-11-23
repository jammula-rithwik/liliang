package com.liliang.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.CadencePeriod
import com.liliang.app.data.CadenceScope
import com.liliang.app.data.GoalKind
import com.liliang.app.data.parseLeadingDouble
import com.liliang.app.data.parseLeadingInt
import com.liliang.app.data.formatDuration
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.CadenceTally
import com.liliang.app.design.GhostButton
import com.liliang.app.design.GoldBurst
import com.liliang.app.design.HanziWatermark
import com.liliang.app.design.InkArc
import com.liliang.app.design.InkCard
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.RevealLedger
import com.liliang.app.design.SealButton
import com.liliang.app.design.Stamp
import com.liliang.app.design.goldShimmerOnce
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.rememberRevealLedger
import com.liliang.app.engine.GoalProgress

/**
 * 志 — the goals scroll. Each goal is a dry-brush arc with its numbers in
 * the margin; an achieved goal slams a gold 达成 stamp the first time it's
 * seen. Inspiration goals are just words, set large.
 */
@Composable
fun GoalsScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.refreshGoalProgress() }
    val goals = viewModel.goalProgressList
    val ledger = rememberRevealLedger()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
    ) {
        item("top") {
            Column(Modifier.statusBarsPadding()) {
                GhostButton("‹ Back", onClick = onBack)
                Spacer(Modifier.height(InkSpacing.s))
                Box(Modifier.fillMaxWidth()) {
                    HanziWatermark(
                        "志",
                        fontSize = 132.dp,
                        color = InkColors.Vermillion.copy(alpha = 0.07f),
                        modifier = Modifier.align(Alignment.CenterEnd).offset(x = 10.dp, y = (-6).dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText("WHAT YOU TRAIN TOWARD", style = InkType.Label, color = InkColors.Vermillion)
                            Spacer(Modifier.height(2.dp))
                            InkText("Goals", style = InkType.Masthead)
                        }
                        SealButton("New", hanzi = "立", compact = true, onClick = { viewModel.loadGoalEditor(null) })
                    }
                }
                Spacer(Modifier.height(InkSpacing.m))
                BrushDivider(seed = 33)
            }
        }

        if (goals.isEmpty()) {
            item("empty") {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    HanziWatermark("空", fontSize = 120.dp)
                    InkText("No goals yet — set one.", style = InkType.BodyDim, color = InkColors.BoneFaint)
                }
            }
        }

        items(goals.size) { i ->
            val view = LocalView.current
            GoalCard(
                progress = goals[i],
                ledger = ledger,
                index = i,
                onEdit = { viewModel.loadGoalEditor(goals[i].goal) },
                onToggleAchieved = {
                    com.liliang.app.platform.Haptics.confirm(view)
                    viewModel.setGoalAchieved(goals[i].goal.id, !goals[i].goal.isAchieved)
                },
            )
        }
    }
}

@Composable
private fun GoalCard(progress: GoalProgress, ledger: RevealLedger, index: Int, onEdit: () -> Unit, onToggleAchieved: () -> Unit) {
    val goal = progress.goal
    val achieved = progress.isAchieved
    // Long-press carves the goal as achieved (or un-carves it) — reversible, so no
    // confirmation; the toast explains what happened. Tap still opens the editor.
    val cardModifier = Modifier
        .fillMaxWidth()
        .inkBleedIn(key = "goalcard:${goal.id}", ledger = ledger, delayMillis = index * 70)
        .pointerInput(goal.id) { detectTapGestures(onLongPress = { onToggleAchieved() }) }

    if (goal.kind == GoalKind.INSPIRATION) {
        InkCard(modifier = cardModifier, onClick = onEdit, accent = InkColors.Gold) {
            InkText("INSPIRATION", style = InkType.Label, color = InkColors.Gold)
            Spacer(Modifier.height(InkSpacing.s))
            InkText(goal.title.ifBlank { goal.notes }, style = InkType.Title.copy(fontSize = 26.sp))
            if (goal.notes.isNotBlank() && goal.title.isNotBlank()) {
                Spacer(Modifier.height(InkSpacing.xs))
                InkText(goal.notes, style = InkType.Body, color = InkColors.BoneDim)
            }
        }
        return
    }

    if (goal.kind == GoalKind.CADENCE) {
        CadenceGoalCard(progress = progress, ledger = ledger, modifier = cardModifier, onEdit = onEdit)
        return
    }

    InkCard(modifier = cardModifier, onClick = onEdit, accent = if (achieved) InkColors.Gold else InkColors.Vermillion) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.l)) {
            Box(contentAlignment = Alignment.Center) {
                InkArc(progress = progress.percent, key = "goal:${goal.id}:${(progress.percent * 100).toInt()}", size = 72.dp) {
                    InkText("${(progress.percent * 100).toInt()}%", style = InkType.NumberSmall.copy(fontSize = 15.sp), color = if (achieved) InkColors.Gold else InkColors.Bone)
                }
                if (achieved) {
                    GoldBurst(trigger = if (ledger.shouldFire("goalburst:${goal.id}")) goal.id else null, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkText(goal.title, style = InkType.Heading, maxLines = 2, modifier = Modifier.weight(1f))
                    if (achieved) {
                        Stamp("达成", tint = InkColors.Gold, filled = true, size = 40.dp, modifier = Modifier.goldShimmerOnce(key = "goalstamp:${goal.id}", ledger = ledger))
                    }
                }
                if (progress.currentLabel.isNotBlank() || progress.targetLabel.isNotBlank()) {
                    InkText(
                        listOf(progress.currentLabel, progress.targetLabel).filter { it.isNotBlank() }.joinToString("  →  "),
                        style = InkType.BodyDim,
                        color = InkColors.BoneDim,
                    )
                }
                if (progress.detail.isNotBlank()) {
                    InkText(progress.detail, style = InkType.Label.copy(letterSpacing = 0.4.sp), color = InkColors.BoneFaint)
                }
                if (progress.etaLabel.isNotBlank() && !achieved) {
                    InkText(progress.etaLabel, style = InkType.Label, color = InkColors.Mist)
                }
            }
        }
    }
}

/**
 * Cadence goal — "Run twice a week", "15 workouts a month". The progress is a
 * row of ink beads (filled this period, hollow remaining) under a big count,
 * which reads at a glance and feels like seals pressed as you train.
 */
@Composable
private fun CadenceGoalCard(progress: GoalProgress, ledger: RevealLedger, modifier: Modifier, onEdit: () -> Unit) {
    val goal = progress.goal
    val met = progress.isAchieved
    val accent = if (met) InkColors.Gold else InkColors.Vermillion
    val scopeLabel = if (goal.cadenceScope == CadenceScope.RUNNING) "RUNS" else "ANY WORKOUT"
    val periodLabel = if (goal.cadencePeriod == CadencePeriod.WEEK) "THIS WEEK" else "THIS MONTH"

    InkCard(modifier = modifier, onClick = onEdit, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText("CADENCE · $scopeLabel", style = InkType.Label, color = accent, modifier = Modifier.weight(1f))
            if (met) {
                Stamp("达成", tint = InkColors.Gold, filled = true, size = 40.dp, modifier = Modifier.goldShimmerOnce(key = "goalstamp:${goal.id}", ledger = ledger))
            }
        }
        Spacer(Modifier.height(InkSpacing.xs))
        InkText(goal.title, style = InkType.Heading, maxLines = 2)
        Spacer(Modifier.height(InkSpacing.m))
        Row(verticalAlignment = Alignment.Bottom) {
            InkText("${progress.currentCount}", style = InkType.NumberLarge, color = if (met) InkColors.Gold else InkColors.Bone)
            InkText(" / ${progress.targetCount}", style = InkType.Title.copy(fontSize = 22.sp), color = InkColors.BoneDim, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            InkText(periodLabel, style = InkType.Label, color = InkColors.BoneFaint, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(InkSpacing.s))
        CadenceTally(
            current = progress.currentCount,
            target = progress.targetCount,
            dotSize = if (progress.targetCount > 12) 14.dp else 20.dp,
        )
    }
}

/** The goal editor — kind chips drive which fields show. */
@Composable
fun GoalEditorScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val goal = viewModel.goalEditor
    val config = viewModel.configSnapshot
    var showExercisePicker by rememberSaveable { mutableStateOf(false) }
    var showDurationPicker by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader(if (goal.id == 0L) "New goal" else "Edit goal", onBack = onBack)
        }
        item("kind") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
            ) {
                GoalKind.entries.forEach { kind ->
                    InkChip(text = kind.label, selected = goal.kind == kind, onClick = { viewModel.updateGoalEditor { it.copy(kind = kind) } })
                }
            }
        }
        item("desc") {
            InkText(kindDescription(goal.kind), style = InkType.BodyDim, color = InkColors.BoneFaint)
        }
        item("title") {
            InkTextField(label = "Title (optional)", value = goal.title, onValueChange = { v -> viewModel.updateGoalEditor { it.copy(title = v) } })
        }
        item("fields") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                when (goal.kind) {
                    GoalKind.LIFT_TARGET -> {
                        InkPickerPill(label = "Exercise", value = goal.exerciseName, onClick = { showExercisePicker = true }, modifier = Modifier.fillMaxWidth())
                        InkDecimalField(key = "gw", label = "Target weight (kg)", value = editableDouble(goal.targetWeightKg), onValueChange = { raw -> viewModel.updateGoalEditor { it.copy(targetWeightKg = parseLeadingDouble(raw)) } })
                    }
                    GoalKind.RUN_TIME -> {
                        InkDecimalField(key = "gd", label = "Distance (km)", value = editableDouble(goal.targetDistanceKm), onValueChange = { raw -> viewModel.updateGoalEditor { it.copy(targetDistanceKm = parseLeadingDouble(raw)) } })
                        InkPickerPill(label = "Target time", value = goal.targetDurationSeconds?.let { formatDuration(it) } ?: "", onClick = { showDurationPicker = true }, modifier = Modifier.fillMaxWidth())
                    }
                    GoalKind.RUN_DISTANCE -> {
                        InkDecimalField(key = "gd2", label = "Distance (km)", value = editableDouble(goal.targetDistanceKm), onValueChange = { raw -> viewModel.updateGoalEditor { it.copy(targetDistanceKm = parseLeadingDouble(raw)) } })
                    }
                    GoalKind.BODYWEIGHT -> {
                        InkDecimalField(key = "gbw", label = "Target body weight (kg)", value = editableDouble(goal.targetWeightKg), onValueChange = { raw -> viewModel.updateGoalEditor { it.copy(targetWeightKg = parseLeadingDouble(raw)) } })
                    }
                    GoalKind.CADENCE -> {
                        InkDecimalField(
                            key = "gcad",
                            label = "How many times",
                            value = goal.targetSessionsPerWeek?.toString().orEmpty(),
                            onValueChange = { raw -> viewModel.updateGoalEditor { it.copy(targetSessionsPerWeek = parseLeadingInt(raw)) } },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
                            InkText("WHAT COUNTS", style = InkType.Label, color = InkColors.BoneFaint)
                            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                                CadenceScope.entries.forEach { scope ->
                                    InkChip(
                                        text = scope.label,
                                        selected = goal.cadenceScope == scope,
                                        onClick = { viewModel.updateGoalEditor { it.copy(cadenceScope = scope) } },
                                    )
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
                            InkText("PER", style = InkType.Label, color = InkColors.BoneFaint)
                            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                                CadencePeriod.entries.forEach { period ->
                                    InkChip(
                                        text = period.label,
                                        selected = goal.cadencePeriod == period,
                                        onClick = { viewModel.updateGoalEditor { it.copy(cadencePeriod = period) } },
                                    )
                                }
                            }
                        }
                    }
                    GoalKind.DISCIPLINE_SESSIONS -> {
                        InkDropdownField(label = "Discipline", selectedValue = goal.discipline.takeIf { it.isNotBlank() }, options = config.disciplines, onSelect = { sel -> viewModel.updateGoalEditor { it.copy(discipline = sel) } })
                        InkDecimalField(key = "gspw", label = "Sessions per week", value = goal.targetSessionsPerWeek?.toString().orEmpty(), onValueChange = { raw -> viewModel.updateGoalEditor { it.copy(targetSessionsPerWeek = parseLeadingInt(raw)) } })
                    }
                    GoalKind.FORM_MASTERY -> {
                        LaunchedEffect(Unit) { viewModel.refreshMartialArtsForms() }
                        val forms = viewModel.martialArtsForms
                        InkDropdownField(
                            label = "Form",
                            selectedValue = forms.firstOrNull { it.id == goal.formId }?.displayShortTitle,
                            options = forms.map { it.displayShortTitle },
                            onSelect = { sel ->
                                val form = forms.firstOrNull { it.displayShortTitle == sel }
                                viewModel.updateGoalEditor { it.copy(formId = form?.id.orEmpty()) }
                            },
                        )
                    }
                    GoalKind.INSPIRATION -> Unit
                }
            }
        }
        item("notes") {
            InkTextField(label = "Notes", value = goal.notes, minLines = 2, singleLine = false, onValueChange = { v -> viewModel.updateGoalEditor { it.copy(notes = v) } })
        }
        item("actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                SealButton("Save goal", hanzi = "立", onClick = { viewModel.saveGoalEditor() })
                if (goal.id != 0L) {
                    GhostButton("Delete", onClick = { pendingDelete = true }, tint = InkColors.Vermillion)
                }
            }
        }
    }

    if (showExercisePicker) {
        SearchSelectionDialog(
            title = "Choose exercise",
            options = config.exercises.map { it.name },
            allowCustomEntry = true,
            customLabel = "Exercise",
            onDismiss = { showExercisePicker = false },
            onSelect = { sel ->
                viewModel.updateGoalEditor { it.copy(exerciseName = sel) }
                showExercisePicker = false
            },
        )
    }
    if (showDurationPicker) {
        DurationPickerDialog(
            title = "Target time",
            totalSeconds = goal.targetDurationSeconds,
            onDismiss = { showDurationPicker = false },
            onConfirm = { seconds ->
                viewModel.updateGoalEditor { it.copy(targetDurationSeconds = seconds) }
                showDurationPicker = false
            },
        )
    }
    if (pendingDelete) {
        ConfirmationDialog(
            title = "Delete ${goal.title.ifBlank { "this goal" }}?",
            message = "Removes the goal and its progress tracking. History isn't touched.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = false },
            onConfirm = {
                viewModel.deleteGoal(goal.id)
                pendingDelete = false
            },
        )
    }
}

private fun kindDescription(kind: GoalKind): String = when (kind) {
    GoalKind.LIFT_TARGET -> "Hit a weight on an exercise. The engine plans that lift toward it and calls a test day when you're close."
    GoalKind.RUN_TIME -> "Cover a distance in a time. Pulls interval and tempo work into your run suggestions, paced off this goal."
    GoalKind.RUN_DISTANCE -> "Complete a distance. Your weekly long run grows toward it."
    GoalKind.BODYWEIGHT -> "Reach a body weight, tracked from your logged weigh-ins."
    GoalKind.CADENCE -> "Show up a set number of times — \"run twice a week\", \"15 workouts a month\". Counted from your training days, shown on the dashboard."
    GoalKind.DISCIPLINE_SESSIONS -> "Practice a discipline N times a week — measured from your logged sessions."
    GoalKind.FORM_MASTERY -> "Finish learning a form, measured from the move tracker."
    GoalKind.INSPIRATION -> "Words to train by. No metrics — shown where you'll see them."
}
