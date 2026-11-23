package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.Overlay
import com.liliang.app.data.PlanDayType
import com.liliang.app.data.WorkoutPlanDay
import com.liliang.app.data.WorkoutTemplate
import com.liliang.app.data.parseLeadingInt
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton
import com.liliang.app.design.Stamp

@Composable
fun PlansScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val plans = viewModel.configSnapshot.plans
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader(
                "Plans",
                subtitle = "历 · the weeks you've shaped",
                onBack = onBack,
                action = {
                    SealButton("New", hanzi = "立", compact = true, onClick = {
                        viewModel.loadPlanEditor(null)
                        viewModel.pushOverlay(Overlay.PlanEditor)
                    })
                },
            ) {
                Spacer(Modifier.height(InkSpacing.xs))
                GhostButton("⚙ Tune the forge", onClick = { viewModel.pushOverlay(Overlay.EngineSettings) })
            }
        }
        if (plans.isEmpty()) {
            item("empty") {
                InkText("No plans yet. A plan paints your calendar and lets the coach follow along.", style = InkType.BodyDim, color = InkColors.BoneFaint)
            }
        }
        items(plans.size) { i ->
            val plan = plans[i]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.card)
                    .background(InkColors.PaperRaised)
                    .border(1.dp, if (plan.isActive) InkColors.Vermillion.copy(alpha = 0.5f) else InkColors.Line, InkShapes.card)
                    .clickable {
                        viewModel.loadPlanEditor(plan)
                        viewModel.pushOverlay(Overlay.PlanEditor)
                    }
                    .padding(InkSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                        InkText(plan.name.ifBlank { "Plan" }, style = InkType.Heading)
                        if (plan.isActive) Stamp("行", tint = InkColors.Vermillion, filled = true, size = 24.dp)
                    }
                    InkText(
                        buildString {
                            append("${plan.weeks} week${if (plan.weeks == 1) "" else "s"}")
                            if (plan.repeatWeekly) append(" · repeating")
                            append(if (plan.useEngine) " · coach on" else " · coach off")
                        },
                        style = InkType.Label,
                        color = InkColors.BoneFaint,
                    )
                }
                InkText("→", style = InkType.Body, color = InkColors.BoneFaint)
            }
        }
    }
}

private val DayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun PlanEditorScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val plan = viewModel.planEditor
    val templates = viewModel.plannerSnapshot.templates
    val week = viewModel.plannerWeekIndex
    val daysThisWeek = plan.days.filter { it.weekIndex == week }.sortedBy { it.dayIndex }
    var pendingDelete by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader(if (plan.id == 0L) "New plan" else "Edit plan", onBack = onBack)
        }
        item("meta") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                InkTextField(label = "Name", value = plan.name, onValueChange = { v -> viewModel.updatePlanEditor { it.copy(name = v) } })
                InkTextField(label = "Start date (YYYY-MM-DD)", value = plan.startDate, onValueChange = { v -> viewModel.updatePlanEditor { it.copy(startDate = v) } })
                ToggleRow("Active plan", "Paints the calendar and feeds the coach.", plan.isActive) { viewModel.updatePlanEditor { it.copy(isActive = !plan.isActive) } }
                ToggleRow("Repeat weekly", "Loop the week grid instead of ending.", plan.repeatWeekly) { viewModel.updatePlanEditor { it.copy(repeatWeekly = !plan.repeatWeekly) } }
                ToggleRow("Coach (engine)", "Off = raw template targets, no prescriptions.", plan.useEngine) { viewModel.updatePlanEditor { it.copy(useEngine = !plan.useEngine) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                    InkText("WEEKS", style = InkType.Label, color = InkColors.BoneFaint)
                    GhostButton("−", onClick = { viewModel.setPlanWeeks(plan.weeks - 1) })
                    InkText("${plan.weeks}", style = InkType.NumberSmall.copy(fontSize = 18.sp))
                    GhostButton("+", onClick = { viewModel.setPlanWeeks(plan.weeks + 1) })
                }
            }
        }
        if (plan.weeks > 1) {
            item("weeknav") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                    GhostButton("‹ Week", onClick = { viewModel.shiftPlannerWeek(-1) })
                    InkText("Week ${week + 1} / ${plan.weeks}", style = InkType.Heading)
                    GhostButton("Week ›", onClick = { viewModel.shiftPlannerWeek(1) })
                }
            }
        }
        items(daysThisWeek.size) { i ->
            val day = daysThisWeek[i]
            PlanDayRow(
                day = day,
                templates = templates,
                onSetRest = { viewModel.updatePlanDay(week, day.dayIndex) { it.copy(dayType = PlanDayType.REST, templateId = null, templateName = "", customWorkoutName = "") } },
                onSetTemplate = { t -> viewModel.updatePlanDay(week, day.dayIndex) { it.copy(dayType = PlanDayType.TEMPLATE, templateId = t.id, templateName = t.name, customWorkoutName = "") } },
                onSetCustom = { name -> viewModel.updatePlanDay(week, day.dayIndex) { it.copy(dayType = PlanDayType.CUSTOM, templateId = null, templateName = "", customWorkoutName = name) } },
            )
        }
        item("actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                SealButton("Save plan", hanzi = "存", enabled = plan.name.isNotBlank() && plan.startDate.isNotBlank(), onClick = { viewModel.savePlanEditor() })
                if (plan.id != 0L) GhostButton("Delete", onClick = { pendingDelete = true }, tint = InkColors.Vermillion)
            }
        }
    }

    if (pendingDelete) {
        ConfirmationDialog(
            title = "Delete plan?",
            message = "Removes the plan and its calendar painting.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = false },
            onConfirm = {
                viewModel.deletePlan(plan.id)
                pendingDelete = false
            },
        )
    }
}

@Composable
private fun PlanDayRow(
    day: WorkoutPlanDay,
    templates: List<WorkoutTemplate>,
    onSetRest: () -> Unit,
    onSetTemplate: (WorkoutTemplate) -> Unit,
    onSetCustom: (String) -> Unit,
) {
    var showPicker by rememberSaveable(day.id) { mutableStateOf(false) }
    var customName by rememberSaveable(day.id) { mutableStateOf(day.customWorkoutName) }
    val label = DayLabels.getOrElse(day.dayIndex) { day.dayLabel }
    val summary = when (day.dayType) {
        PlanDayType.REST -> "Rest"
        PlanDayType.TEMPLATE -> day.templateName.ifBlank { "Workout" }
        PlanDayType.CUSTOM -> day.customWorkoutName.ifBlank { "New workout" }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.chip)
            .border(1.dp, if (day.dayType == PlanDayType.REST) InkColors.Line else InkColors.Vermillion.copy(alpha = 0.35f), InkShapes.chip)
            .clickable { showPicker = true }
            .padding(InkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp)) {
            InkText(label, style = InkType.Label, color = InkColors.Vermillion)
        }
        InkText(summary, style = InkType.BodyStrong, color = if (day.dayType == PlanDayType.REST) InkColors.BoneFaint else InkColors.Bone, modifier = Modifier.weight(1f))
        InkText("edit", style = InkType.Label, color = InkColors.BoneFaint)
    }
    if (showPicker) {
        InkDialog(title = "$label — choose", onDismiss = { showPicker = false }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.chip)
                    .clickable { onSetRest(); showPicker = false }
                    .padding(InkSpacing.m),
            ) {
                Stamp("休", tint = InkColors.BoneFaint, size = 28.dp)
                Spacer(Modifier.width(InkSpacing.m))
                InkText("Rest day", style = InkType.BodyStrong)
            }
            if (templates.isNotEmpty()) {
                InkText("WORKOUTS", style = InkType.Label, color = InkColors.BoneFaint)
                templates.forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(InkShapes.chip)
                            .border(1.dp, InkColors.Line, InkShapes.chip)
                            .clickable { onSetTemplate(t); showPicker = false }
                            .padding(InkSpacing.m),
                    ) {
                        InkText(t.name, style = InkType.BodyStrong, modifier = Modifier.weight(1f))
                        InkText("${t.items.size}", style = InkType.Label, color = InkColors.BoneFaint)
                    }
                }
            }
            // A named day without a saved workout — "Hill sprints", "Open mat".
            InkText("OR NAME THE DAY", style = InkType.Label, color = InkColors.BoneFaint)
            InkTextField(label = "Custom workout name", value = customName, onValueChange = { customName = it })
            Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                SealButton(
                    "Set custom day",
                    hanzi = "立",
                    compact = true,
                    enabled = customName.isNotBlank(),
                    onClick = {
                        onSetCustom(customName.trim())
                        customName = ""
                        showPicker = false
                    },
                )
                GhostButton("Cancel", onClick = { showPicker = false })
            }
        }
    }
}

@Composable
fun EngineSettingsScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val s = viewModel.planSettingsEditor
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader("Tuning the forge", subtitle = "the dials behind the prescriptions", onBack = onBack)
        }
        item("emphasis") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("EMPHASIS (relative)", style = InkType.Label, color = InkColors.BoneFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    EngineNumber("Strength", s.emphasisStrength.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(emphasisStrength = parseLeadingInt(v) ?: 0) } }
                    EngineNumber("Hypertrophy", s.emphasisHypertrophy.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(emphasisHypertrophy = parseLeadingInt(v) ?: 0) } }
                    EngineNumber("Power", s.emphasisPower.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(emphasisPower = parseLeadingInt(v) ?: 0) } }
                }
            }
        }
        item("steps") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("LOAD STEPS (kg)", style = InkType.Label, color = InkColors.BoneFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    EngineNumber("Primary", editableDouble(s.primaryWeightStepKg), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(primaryWeightStepKg = parseEditableDecimal(v) ?: 2.5) } }
                    EngineNumber("Secondary", editableDouble(s.secondaryWeightStepKg), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(secondaryWeightStepKg = parseEditableDecimal(v) ?: 2.5) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    EngineNumber("Acc rep step", s.accessoryRepStep.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(accessoryRepStep = parseLeadingInt(v) ?: 1) } }
                    EngineNumber("Acc set step", s.accessorySetStep.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(accessorySetStep = parseLeadingInt(v) ?: 0) } }
                }
            }
        }
        item("running") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("RUNNING", style = InkType.Label, color = InkColors.BoneFaint)
                // weeklyRunKmTarget drove real coach behaviour ("already at X of
                // your Y km week") yet was settable NOWHERE — null forever.
                EngineNumber("Weekly km target (blank = none)", editableDouble(s.weeklyRunKmTarget), Modifier.fillMaxWidth()) { v ->
                    viewModel.updatePlanSettingsEditor { it.copy(weeklyRunKmTarget = parseEditableDecimal(v)) }
                }
            }
        }
        item("schemes") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("SCHEMES", style = InkType.Label, color = InkColors.BoneFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    EngineNumber("Str sets", s.strengthSets.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(strengthSets = parseLeadingInt(v) ?: 5) } }
                    EngineNumber("Str reps", s.strengthReps.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(strengthReps = parseLeadingInt(v) ?: 5) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    EngineNumber("Hyp min", s.hypRepMin.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(hypRepMin = parseLeadingInt(v) ?: 8) } }
                    EngineNumber("Hyp max", s.hypRepMax.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(hypRepMax = parseLeadingInt(v) ?: 12) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    EngineNumber("Pwr %e1RM", s.powerPercent.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(powerPercent = parseLeadingInt(v) ?: 50) } }
                    EngineNumber("Pwr sets", s.powerSets.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(powerSets = parseLeadingInt(v) ?: 3) } }
                }
            }
        }
        item("recovery") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("RECOVERY", style = InkType.Label, color = InkColors.BoneFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    EngineNumber("Deload after stalls", s.deloadAfterStalls.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(deloadAfterStalls = parseLeadingInt(v) ?: 6) } }
                    EngineNumber("Layoff days", s.layoffDays.toString(), Modifier.weight(1f)) { v -> viewModel.updatePlanSettingsEditor { it.copy(layoffDays = parseLeadingInt(v) ?: 21) } }
                }
            }
        }
        item("save") {
            SealButton("Save settings", hanzi = "存", onClick = { viewModel.savePlanSettingsEditor() })
        }
    }
}

@Composable
private fun EngineNumber(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    InkDecimalField(key = "eng:$label", label = label, value = value, modifier = modifier, onValueChange = onChange)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.chip)
            .border(1.dp, InkColors.Line, InkShapes.chip)
            .padding(InkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            InkText(title, style = InkType.BodyStrong)
            InkText(subtitle, style = InkType.Label, color = InkColors.BoneFaint)
        }
        InkToggle(checked = checked, onCheckedChange = { onToggle() })
    }
}
