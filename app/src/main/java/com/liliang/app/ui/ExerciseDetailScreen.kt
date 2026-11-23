package com.liliang.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.WorkoutGroup
import com.liliang.app.data.WorkoutSetRecord
import com.liliang.app.data.formatWeight
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.ChartPoint
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkAreaChart
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.NumberBlock
import com.liliang.app.design.Stamp
import com.liliang.app.design.inkBleedIn
import com.liliang.app.design.rememberRevealLedger

/**
 * 史 — one lift's whole story. The bests as big serif numerals, the estimated
 * 1RM as an ink wash you can scrub, the last session ready to copy from, and
 * the recent ledger underneath. Reached by tapping a featured lift or a PR.
 */
@Composable
fun ExerciseDetailScreen(viewModel: LiliangViewModel, name: String, onBack: () -> Unit) {
    // The dashboard load carries the summary; request it if we landed here
    // with a different (or no) exercise selected.
    LaunchedEffect(name) {
        if (viewModel.dashboardSnapshot.selectedExerciseSummary?.exerciseName != name) {
            viewModel.refreshDashboard(selectedExercise = name)
        }
    }
    val summary = viewModel.dashboardSnapshot.selectedExerciseSummary?.takeIf { it.exerciseName == name }
    val ledger = rememberRevealLedger()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
    ) {
        item("top") {
            Column(Modifier.statusBarsPadding().inkBleedIn(key = "exdetail:$name")) {
                GhostButton("‹ Back", onClick = onBack)
                Spacer(Modifier.height(InkSpacing.m))
                InkText("史 · TRAINING RECORD", style = InkType.Label, color = InkColors.Vermillion)
                Spacer(Modifier.height(2.dp))
                InkText(name, style = InkType.Masthead, maxLines = 2)
                summary?.lastPerformedDate?.takeIf { it.isNotBlank() }?.let {
                    InkText("last trained ${formatShortDate(it)}", style = InkType.Label, color = InkColors.BoneFaint)
                }
                Spacer(Modifier.height(InkSpacing.s))
                BrushDivider(seed = name.hashCode())
            }
        }

        if (summary == null) {
            item("loading") {
                InkText("Reading the ledger…", style = InkType.BodyDim, color = InkColors.BoneFaint)
            }
        } else {
            item("numbers") {
                NumberRow(summary = summary)
            }

            // Timed holds get their own spine — an e1RM chart means nothing
            // for a plank; the per-session longest hold is the story.
            val holdSpine = summary.logFormat != com.liliang.app.data.LogFormat.REPS && summary.durationTrend.size >= 2
            if (holdSpine) {
                item("chart") {
                    Column {
                        InkText(
                            "LONGEST HOLD · LAST ${summary.durationTrend.size} DAYS TRAINED",
                            style = InkType.Label,
                            color = InkColors.BoneFaint,
                        )
                        Spacer(Modifier.height(InkSpacing.s))
                        InkAreaChart(
                            points = summary.durationTrend.map { point ->
                                ChartPoint(
                                    value = point.value.toFloat(),
                                    readout = "${com.liliang.app.data.formatDuration(point.value.toInt())} · ${formatShortDate(point.date)}",
                                )
                            },
                            accent = InkColors.Jade,
                            key = "hold:$name:${summary.durationTrend.size}",
                            ledger = ledger,
                            height = 150.dp,
                        )
                    }
                }
            } else if (summary.e1rmTrend.size >= 2) {
                item("chart") {
                    Column {
                        InkText(
                            "ESTIMATED 1RM · LAST ${summary.e1rmTrend.size} SESSIONS",
                            style = InkType.Label,
                            color = InkColors.BoneFaint,
                        )
                        Spacer(Modifier.height(InkSpacing.s))
                        InkAreaChart(
                            points = summary.e1rmTrend.map { point ->
                                ChartPoint(
                                    value = point.value.toFloat(),
                                    readout = "${formatWeight(point.value)} · ${formatShortDate(point.date)}",
                                )
                            },
                            accent = InkColors.Iron,
                            key = "e1rm:$name:${summary.e1rmTrend.size}",
                            ledger = ledger,
                            height = 150.dp,
                        )
                    }
                }
            }

            if (summary.hint.lastSessionSets.isNotEmpty()) {
                item("last") {
                    Column {
                        InkText(
                            "LAST TIME · ${formatShortDate(summary.hint.lastSessionDate)}",
                            style = InkType.Label,
                            color = InkColors.BoneFaint,
                        )
                        Spacer(Modifier.height(InkSpacing.s))
                        SetPillRow(sets = summary.hint.lastSessionSets)
                    }
                }
            }

            if (summary.recentWorkouts.isNotEmpty()) {
                item("ledger-label") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                        Stamp("录", tint = InkColors.Gold, size = 26.dp)
                        InkText("RECENT SESSIONS", style = InkType.Label, color = InkColors.Gold)
                    }
                }
                items(summary.recentWorkouts.size) { i ->
                    SessionLedgerRow(
                        workout = summary.recentWorkouts[i],
                        exerciseName = name,
                        index = i,
                        ledger = ledger,
                    )
                }
            }
        }
    }
}

/** The bests, set large — only what exists gets a numeral. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberRow(summary: com.liliang.app.data.ExerciseHistorySummary) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        summary.hint.bestE1rm?.let {
            NumberBlock(value = trimWeight(it), unit = "BEST e1RM", style = InkType.NumberLarge)
        }
        summary.bestWeight?.let {
            NumberBlock(value = trimWeight(it), unit = "BEST LIFT", style = InkType.NumberLarge)
        }
        summary.bestDurationSeconds?.let {
            NumberBlock(value = com.liliang.app.data.formatDuration(it), unit = "LONGEST HOLD", style = InkType.NumberLarge)
        }
        NumberBlock(value = "${summary.workoutCount}", unit = "SESSIONS", style = InkType.NumberLarge)
        NumberBlock(value = "${summary.totalSets}", unit = "SETS ALL-TIME", style = InkType.NumberLarge)
    }
}

/** One session's sets as bone pills — "80×5" reads at arm's length. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetPillRow(sets: List<WorkoutSetRecord>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
    ) {
        sets.forEach { set ->
            InkText(
                setReading(set),
                style = InkType.NumberSmall.copy(fontSize = 16.sp),
                color = InkColors.Bone,
            )
        }
    }
}

private fun setReading(set: WorkoutSetRecord): String {
    val weight = set.weight?.let(::trimWeight)
    val reps = set.repsText?.takeIf { it.isNotBlank() }
    return when {
        weight != null && reps != null -> "$weight×$reps"
        weight != null -> weight
        reps != null -> "×$reps"
        // Timed holds and distance work read as themselves, not as em dashes.
        set.durationSeconds != null -> com.liliang.app.data.formatDuration(set.durationSeconds)
        set.distanceKm != null -> com.liliang.app.data.formatDistance(set.distanceKm)
        else -> "—"
    }
}

/** One past session: the date, this exercise's sets, the volume it moved. */
@Composable
private fun SessionLedgerRow(
    workout: WorkoutGroup,
    exerciseName: String,
    index: Int,
    ledger: com.liliang.app.design.RevealLedger,
) {
    val sets = workout.entries
        .filter {
            it.title.equals(exerciseName, ignoreCase = true) ||
                it.title.startsWith("$exerciseName:", ignoreCase = true)
        }
        .flatMap { it.sets }
    if (sets.isEmpty()) return
    val volume = sets.sumOf { (it.weight ?: 0.0) * (com.liliang.app.data.parseLeadingInt(it.repsText) ?: 0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .inkBleedIn(key = "exrow:$exerciseName:${workout.date}", ledger = ledger, delayMillis = index * 60)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            InkText(formatShortDate(workout.date), style = InkType.Body)
            InkText(
                sets.joinToString(" · ") { setReading(it) },
                style = InkType.NumberSmall.copy(fontSize = 13.sp),
                color = InkColors.BoneDim,
                maxLines = 2,
            )
        }
        if (volume > 0) {
            InkText(formatWeight(volume), style = InkType.Label, color = InkColors.BoneFaint)
        }
    }
}
