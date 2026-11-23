package com.liliang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.formatDuration
import com.liliang.app.data.formatPace
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.InkWood
import com.liliang.app.design.SealButton
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.swingIn
import com.liliang.app.design.woodGrain
import com.liliang.app.engine.RunKind
import com.liliang.app.engine.RunPrescription
import com.liliang.app.platform.Haptics
import kotlin.math.roundToInt

// Standalone activity logging — a run or a martial-arts session without the
// full session ceremony. These are rooms of the same hall as Today: a carved
// beam overhead, choices as wooden tags swinging in, the working number set
// huge in brush-and-serif, and one seal to press. No stacked cards.

private val RunTitles = listOf(RunKind.EASY, RunKind.LONG, RunKind.INTERVALS, RunKind.TEMPO).map { it.label }
private val MartialArtsActivities = listOf("Form practice", "Heavy Bag", "Shadowboxing", "Drills", "Sparring")

/** The lintel above a logging room — same carpentry as Today's beam. */
@Composable
private fun LogBeam(title: String, date: String, hanzi: String, hanziTint: androidx.compose.ui.graphics.Color, onBack: () -> Unit) {
    Column(Modifier.statusBarsPadding()) {
        GhostButton("‹ Back", onClick = onBack)
        Spacer(Modifier.height(InkSpacing.s))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(InkShapes.card)
                .background(InkWood.Beam)
                .woodGrain(seed = title.hashCode())
                .border(1.dp, InkColors.WoodEdge, InkShapes.card)
                .inkBleedIn(key = "logbeam:$title:$date")
                .padding(horizontal = InkSpacing.l, vertical = InkSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                InkText(title, style = InkType.Masthead.copy(fontSize = 28.sp, lineHeight = 32.sp))
                InkText(formatShortDate(date), style = InkType.BodyDim, color = InkColors.BoneDim)
            }
            InkText(hanzi, style = InkType.Seal.copy(fontSize = 30.sp), color = hanziTint)
        }
    }
}

/** 跑 — log a run. A room, not a form: dial the distance, hang the rest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunLogScreen(viewModel: LiliangViewModel, date: String, onBack: () -> Unit) {
    val view = LocalView.current
    val coach = viewModel.quickRunCoach
    var title by rememberSaveable(date) { mutableStateOf(RunTitles.first()) }
    var distanceKm by rememberSaveable(date) { mutableDoubleStateOf(5.0) }
    var durationSeconds by rememberSaveable(date) { mutableStateOf<Int?>(null) }
    var paceSecondsPerKm by rememberSaveable(date) { mutableStateOf<Double?>(null) }
    var notes by rememberSaveable(date) { mutableStateOf("") }
    var showDurationPicker by rememberSaveable(date) { mutableStateOf(false) }
    var showPacePicker by rememberSaveable(date) { mutableStateOf(false) }
    var typeDistance by rememberSaveable(date) { mutableStateOf(false) }

    // Any two of distance/duration/pace determine the third — and the value
    // the user DECLARED wins. Setting a pace then dialing distance used to
    // silently rewrite the pace he just chose.
    var paceExplicit by rememberSaveable(date) { mutableStateOf(false) }
    fun recalcFrom(changed: String) {
        val d = distanceKm
        val t = durationSeconds
        val p = paceSecondsPerKm
        when (changed) {
            "distance" -> if (paceExplicit && p != null && p > 0 && d > 0) {
                durationSeconds = (p * d).roundToInt()
            } else if (d > 0 && t != null && t > 0) {
                paceSecondsPerKm = t / d
            }
            "duration" -> {
                paceExplicit = false
                if (d > 0 && t != null && t > 0) paceSecondsPerKm = t / d
            }
            "pace" -> {
                paceExplicit = true
                if (d > 0 && p != null && p > 0) durationSeconds = (p * d).roundToInt()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
    ) {
        item("beam") { LogBeam(title = "Log a run", date = date, hanzi = "跑", hanziTint = InkColors.Jade, onBack = onBack) }

        if (coach != null) {
            item("coach") { CoachMarginNote(coach) { suggestion ->
                title = suggestion
                coach.distanceKm?.let { distanceKm = it }
                coach.paceSecondsPerKm?.let { paceSecondsPerKm = it.toDouble() }
                recalcFrom("pace")
            } }
        }

        item("kind") {
            FlowRow(
                modifier = Modifier.fillMaxWidth().swingIn(key = "run-tags:$date", fromDegrees = 2f),
                horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
                verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
            ) {
                RunTitles.forEach { option ->
                    WoodTag(text = option, selected = title == option, onClick = { title = option }, accent = InkColors.Jade)
                }
            }
        }

        item("numbers") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // The distance is the workout — set it like a working number.
                WorkingNumber(
                    label = "KM",
                    value = trimWeight(distanceKm),
                    fontSize = 72.sp,
                    onTap = { typeDistance = true },
                    onStep = { s ->
                        distanceKm = (distanceKm + s * 0.5).coerceIn(0.0, 500.0)
                        recalcFrom("distance")
                        Haptics.tick(view)
                    },
                )
                Spacer(Modifier.height(InkSpacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                    InkPickerPill(
                        label = "Time",
                        value = durationSeconds?.let { formatDuration(it) } ?: "",
                        onClick = { showDurationPicker = true },
                        modifier = Modifier.weight(1f),
                    )
                    InkPickerPill(
                        label = "Pace",
                        value = paceSecondsPerKm?.let { formatPace(it) } ?: "",
                        onClick = { showPacePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item("notes") {
            Column {
                BrushDivider(seed = 21)
                Spacer(Modifier.height(InkSpacing.m))
                InkTextField(label = "Notes", value = notes, minLines = 2, singleLine = false, onValueChange = { notes = it })
            }
        }

        item("save") {
            SealButton(
                "Log run",
                hanzi = "跑",
                enabled = distanceKm > 0 || durationSeconds != null,
                tint = InkColors.Jade,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    viewModel.logQuickRun(date, title, distanceKm.takeIf { it > 0 }, durationSeconds, paceSecondsPerKm, notes)
                    onBack()
                },
            )
        }
    }

    if (showDurationPicker) {
        DurationPickerDialog(
            title = "Run time",
            totalSeconds = durationSeconds,
            onDismiss = { showDurationPicker = false },
            onConfirm = { seconds ->
                durationSeconds = seconds
                recalcFrom("duration")
                showDurationPicker = false
            },
        )
    }
    if (showPacePicker) {
        DurationPickerDialog(
            title = "Pace (per km)",
            totalSeconds = paceSecondsPerKm?.roundToInt(),
            onDismiss = { showPacePicker = false },
            onConfirm = { seconds ->
                paceSecondsPerKm = seconds?.toDouble()
                recalcFrom("pace")
                showPacePicker = false
            },
        )
    }
    if (typeDistance) {
        NumberEntryDialog(
            title = "Exact distance (km)",
            initial = trimWeight(distanceKm),
            decimal = true,
            onDismiss = { typeDistance = false },
            onConfirm = { v ->
                distanceKm = v.coerceIn(0.0, 500.0)
                recalcFrom("distance")
                typeDistance = false
            },
        )
    }
}

/** The coach writes in the margin, brush against the rule — never in a card. */
@Composable
private fun CoachMarginNote(coach: RunPrescription, onUse: (title: String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = InkColors.Jade.copy(alpha = 0.7f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                )
            }
            .padding(start = InkSpacing.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InkSpacing.xs)) {
            InkText("THE COACH SUGGESTS · ${coach.kind.label.uppercase()}", style = InkType.Label, color = InkColors.Jade)
            if (coach.rationale.isNotBlank()) {
                InkText(coach.rationale, style = InkType.BodyDim, color = InkColors.BoneDim)
            }
            GhostButton("Take the suggestion", tint = InkColors.Jade, onClick = {
                onUse(coach.kind.label)
            })
        }
    }
}

/** 武 — log a martial session. Same hall, same wood. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MartialLogScreen(viewModel: LiliangViewModel, date: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { if (viewModel.martialArtsForms.isEmpty()) viewModel.refreshMartialArtsForms() }
    val view = LocalView.current
    val disciplines = viewModel.configSnapshot.disciplines
    val forms = viewModel.martialArtsForms
    val formProgress = viewModel.martialArtsFormProgress

    var discipline by rememberSaveable(date) { mutableStateOf("") }
    var activity by rememberSaveable(date) { mutableStateOf(MartialArtsActivities.first()) }
    var minutes by rememberSaveable(date) { mutableIntStateOf(20) }
    var notes by rememberSaveable(date) { mutableStateOf("") }
    var typeMinutes by rememberSaveable(date) { mutableStateOf(false) }
    var checkpointFormId by rememberSaveable(date) { mutableStateOf("") }
    var reachedMove by rememberSaveable(date) { mutableIntStateOf(0) }

    LaunchedEffect(disciplines) {
        if (discipline.isBlank()) discipline = disciplines.firstOrNull().orEmpty()
    }
    val checkpointForm = forms.firstOrNull { it.id == checkpointFormId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
    ) {
        item("beam") { LogBeam(title = "Log martial arts", date = date, hanzi = "武", hanziTint = InkColors.Vermillion, onBack = onBack) }

        item("discipline") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("THE ART", style = InkType.Label, color = InkColors.BoneFaint)
                if (disciplines.isEmpty()) {
                    InkText("No disciplines yet — add one in the Hall first.", style = InkType.Body, color = InkColors.Vermillion)
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().swingIn(key = "ma-arts:$date", fromDegrees = -2f),
                        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
                        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
                    ) {
                        disciplines.forEach { art ->
                            WoodTag(text = art, selected = discipline == art, onClick = { discipline = art }, accent = InkColors.Vermillion)
                        }
                    }
                }
            }
        }

        item("activity") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("THE WORK", style = InkType.Label, color = InkColors.BoneFaint)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().swingIn(key = "ma-work:$date", fromDegrees = 2f, delayMillis = 60),
                    horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
                    verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
                ) {
                    MartialArtsActivities.forEach { option ->
                        WoodTag(text = option, selected = activity == option, onClick = { activity = option }, accent = InkColors.Vermillion)
                    }
                }
            }
        }

        item("duration") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WorkingNumber(
                    label = "MINUTES",
                    value = minutes.toString(),
                    fontSize = 72.sp,
                    onTap = { typeMinutes = true },
                    onStep = { s ->
                        minutes = (minutes + s * 5).coerceIn(0, 600)
                        Haptics.tick(view)
                    },
                )
            }
        }

        if (forms.any { it.moveCount > 0 && !it.isMastered }) {
            item("form") {
                Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                    BrushDivider(seed = 22)
                    Spacer(Modifier.height(InkSpacing.xs))
                    InkText("FORM PROGRESS · OPTIONAL", style = InkType.Label, color = InkColors.BoneFaint)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
                        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
                    ) {
                        WoodTag(text = "None", selected = checkpointFormId.isBlank(), onClick = { checkpointFormId = "" })
                        forms.filter { it.moveCount > 0 && !it.isMastered }.forEach { form ->
                            WoodTag(
                                text = form.displayShortTitle,
                                selected = checkpointFormId == form.id,
                                onClick = {
                                    checkpointFormId = form.id
                                    reachedMove = formProgress[form.id]?.currentlyLearningIndex(form) ?: form.moveCount
                                },
                            )
                        }
                    }
                    if (checkpointForm != null) {
                        val maxMove = checkpointForm.moveCount
                        val move = checkpointForm.moves.firstOrNull { it.index == reachedMove }
                        InkText("Reached move $reachedMove of $maxMove", style = InkType.Body, color = InkColors.BoneDim)
                        move?.let { InkText(it.name, style = InkType.Heading, color = InkColors.Vermillion) }
                        if (maxMove >= 2) {
                            InkSlider(
                                value = reachedMove.coerceIn(1, maxMove),
                                range = 1..maxMove,
                                onValueChange = { reachedMove = it },
                            )
                        }
                    }
                }
            }
        }

        item("notes") {
            InkTextField(label = "Notes", value = notes, minLines = 2, singleLine = false, onValueChange = { notes = it })
        }

        item("save") {
            SealButton(
                "Log session",
                hanzi = "武",
                enabled = discipline.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val exerciseName = if (activity == "Form practice") discipline else activity
                    viewModel.logQuickMartialArts(
                        date,
                        discipline,
                        exerciseName,
                        (minutes * 60).takeIf { it > 0 },
                        notes,
                        checkpointFormId,
                        reachedMove.takeIf { checkpointFormId.isNotBlank() && it > 0 },
                    )
                    onBack()
                },
            )
        }
    }

    if (typeMinutes) {
        NumberEntryDialog(
            title = "Exact minutes",
            initial = minutes.toString(),
            decimal = false,
            onDismiss = { typeMinutes = false },
            onConfirm = { v ->
                minutes = v.roundToInt().coerceIn(0, 600)
                typeMinutes = false
            },
        )
    }
}
