package com.liliang.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.data.ProgressionRole
import com.liliang.app.design.InkCard
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType

/**
 * 役 — exercise roles. Every exercise gets a default role, so adding "Incline
 * Bench Press" arrives as a primary lift instead of always an accessory. The
 * engine reads this default when an exercise is added to a workout.
 */
private val ROLE_CHOICES = listOf(
    ProgressionRole.PRIMARY,
    ProgressionRole.SECONDARY,
    ProgressionRole.ACCESSORY,
    ProgressionRole.NONE,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExerciseRolesScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.refreshConfig() }
    var query by rememberSaveable { mutableStateOf("") }
    val exercises = viewModel.configSnapshot.exercises
        .filter { it.defaultCategory.equals("Running", true).not() && it.defaultCategory.equals("Martial Arts", true).not() }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.usageCount }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            Column {
                EditorHeader("Exercise roles", subtitle = "役 · how each lift is added by default", onBack = onBack) {
                    InkText(
                        "Primary & secondary lifts drive the progression engine; accessories follow rep targets. Auto decides from your pools (accessory if none).",
                        style = InkType.BodyDim,
                        color = InkColors.BoneFaint,
                    )
                }
                Spacer(Modifier.height(InkSpacing.s))
                InkTextField(label = "Search exercises", value = query, onValueChange = { query = it })
            }
        }

        if (exercises.isEmpty()) {
            item("empty") {
                InkText("No exercises found.", style = InkType.BodyDim, color = InkColors.BoneFaint)
            }
        }

        items(exercises.size) { i ->
            val ex = exercises[i]
            InkCard(accent = roleAccent(ex.defaultRole)) {
                InkText(ex.name, style = InkType.Title.copy(fontSize = 18.sp), maxLines = 2)
                if (ex.defaultMuscle.isNotBlank()) {
                    InkText(ex.defaultMuscle, style = InkType.Label, color = InkColors.BoneFaint)
                }
                Spacer(Modifier.height(InkSpacing.s))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
                ) {
                    ROLE_CHOICES.forEach { role ->
                        InkChip(
                            text = role.label,
                            selected = ex.defaultRole == role,
                            onClick = { viewModel.setExerciseDefaultRole(ex.name, role) },
                            tint = roleAccent(role),
                        )
                    }
                }
            }
        }
    }
}

private fun roleAccent(role: ProgressionRole) = when (role) {
    ProgressionRole.PRIMARY -> InkColors.Vermillion
    ProgressionRole.SECONDARY -> InkColors.Gold
    ProgressionRole.ACCESSORY -> InkColors.InkWash
    else -> InkColors.BoneFaint
}
