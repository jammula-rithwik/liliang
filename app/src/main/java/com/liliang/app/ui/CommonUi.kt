package com.liliang.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.liliang.app.data.ComboItem
import com.liliang.app.data.ExerciseCatalogItem
import com.liliang.app.data.LogFormat
import com.liliang.app.data.MartialArtsForm
import com.liliang.app.data.WeightUnit
import com.liliang.app.data.WorkoutSessionSet
import com.liliang.app.data.WorkoutSetRecord
import com.liliang.app.data.formatDistance
import com.liliang.app.data.formatDuration
import com.liliang.app.data.formatWeight
import com.liliang.app.data.parseLeadingDouble
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.LiliangSprings
import com.liliang.app.design.orSnap
import com.liliang.app.design.NumberBlock
import com.liliang.app.design.Stamp
import com.liliang.app.design.woodGrain
import com.liliang.app.design.SealButton
import com.liliang.app.platform.Haptics
import kotlin.math.roundToInt

// Shared interaction primitives for Lìliàng's screens. The gesture logic came
// from workout2-app; the step direction was flipped 29 Aug 2026 (see
// verticalStepDrag) — only the chrome is re-inked.

// ───────────────────────── Gestures ─────────────────────────

/**
 * Vertical drag-to-adjust: DOWN increments, up decrements, one step per
 * ~20dp travelled. Flipped 29 Aug 2026 at Greg's direction — swiping down
 * should raise the number (the up-to-increase it inherited from workout2-app
 * read as inverted on the gym floor). This also makes the InkWheel roll with
 * the finger: its next-larger value ghosts ABOVE the centre, so pulling down
 * drags that value into view. Deltas go to [onStep], which must transform
 * *current* state, never a captured value — pointerInput lambdas outlive
 * recomposition, so reading composed state in here silently goes stale.
 */
fun Modifier.verticalStepDrag(key: Any?, onStep: (Int) -> Unit): Modifier =
    pointerInput(key) {
        val stepPx = 20.dp.toPx()
        var accumulated = 0f
        detectVerticalDragGestures(
            onDragStart = { accumulated = 0f },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                accumulated += dragAmount
                while (accumulated <= -stepPx) {
                    onStep(-1)
                    accumulated += stepPx
                }
                while (accumulated >= stepPx) {
                    onStep(1)
                    accumulated -= stepPx
                }
            },
        )
    }

// ───────────────────────── Field primitives ─────────────────────────

/** Bordered field shell shared by the in-row inputs and pills. */
@Composable
private fun FieldShell(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = modifier
        .clip(InkShapes.chip)
        .border(
            1.dp,
            if (active) InkColors.Vermillion.copy(alpha = 0.6f) else InkColors.Line,
            InkShapes.chip,
        )
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        InkText(label.uppercase(), style = InkType.LabelSmall, color = InkColors.BoneFaint, maxLines = 1)
        content()
    }
}

/**
 * Compact decimal entry for set rows — type to set. Mirrors v2's text-state
 * dance: local text survives IME composition, external value changes (drag
 * steps, applied pills) overwrite it only when they really differ.
 */
@Composable
fun InkDecimalField(
    key: Any,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    big: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var text by rememberSaveable(key) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != text && parseEditableDecimal(text) == parseEditableDecimal(value)) return@LaunchedEffect
        text = value
    }
    FieldShell(label = label, modifier = modifier, active = active) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth(),
            value = text,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() || it == '.' || it == ',' }
                text = filtered
                onValueChange(filtered)
            },
            singleLine = true,
            textStyle = (if (big) InkType.NumberSmall.copy(fontSize = 22.sp) else InkType.NumberSmall)
                .copy(color = InkColors.Bone),
            cursorBrush = SolidColor(InkColors.Vermillion),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            decorationBox = { inner ->
                if (text.isBlank()) {
                    InkText("—", style = InkType.NumberSmall, color = InkColors.BoneFaint)
                }
                inner()
            },
        )
    }
}

/** Tap-to-pick pill (time, pace, combo/form). */
@Composable
fun InkPickerPill(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    FieldShell(label = label, modifier = modifier, active = active, onClick = onClick) {
        InkText(
            value.ifBlank { "—" },
            style = InkType.NumberSmall.copy(fontSize = 15.sp),
            color = if (value.isBlank()) InkColors.BoneFaint else InkColors.Bone,
            maxLines = 1,
        )
    }
}

/** Free-text field on paper — workout names, notes. */
@Composable
fun InkTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    FieldShell(label = label, modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = InkType.Body.copy(color = InkColors.Bone, fontSize = 15.sp),
            cursorBrush = SolidColor(InkColors.Vermillion),
            decorationBox = { inner ->
                if (value.isBlank()) {
                    InkText("…", style = InkType.Body, color = InkColors.BoneFaint)
                }
                inner()
            },
        )
    }
}

/** One-tap toggle chip, ink-styled (replaces M3 FilterChip). */
@Composable
fun InkChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = InkColors.Vermillion,
) {
    val view = LocalView.current
    // The touch target pads out to 48dp (gym-floor control); the visual chip
    // inside stays its usual size.
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) {
                Haptics.tick(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(InkShapes.chip)
                .background(if (selected) tint.copy(alpha = 0.16f) else Color.Transparent)
                .border(1.dp, if (selected) tint else InkColors.Line, InkShapes.chip)
                .padding(horizontal = InkSpacing.m, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            InkText(
                text,
                style = InkType.BodyDim,
                color = if (selected) InkColors.Bone else InkColors.BoneDim,
                maxLines = 1,
            )
        }
    }
}

// ───────────────────────── Dialog shell ─────────────────────────

/** Paper dialog: raised sheet, brush stroke under the title. Every dialog in
 *  the app shares ONE width (full width minus even margins) — wrap-to-content
 *  popups of a dozen different sizes are what made surfaces feel tacked on. */
@Composable
fun InkDialog(
    title: String,
    onDismiss: () -> Unit,
    maxHeight: Int = 620,
    scrollable: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clip(InkShapes.card)
                .background(InkColors.PaperRaised)
                .border(1.dp, InkColors.Line, InkShapes.card)
                .padding(InkSpacing.xl)
                .heightIn(max = maxHeight.dp),
        ) {
            InkText(title, style = InkType.Title)
            Spacer(Modifier.height(InkSpacing.s))
            BrushDivider(seed = title.hashCode())
            Spacer(Modifier.height(InkSpacing.l))
            Column(
                modifier = if (scrollable) {
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier.weight(1f, fill = false)
                },
                verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
                content = content,
            )
        }
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    InkDialog(title = title, onDismiss = onDismiss) {
        InkText(message, style = InkType.Body, color = InkColors.BoneDim)
        Spacer(Modifier.height(InkSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
            GhostButton("Cancel", onClick = onDismiss)
            Spacer(Modifier.weight(1f))
            SealButton(confirmLabel, onClick = onConfirm, compact = true)
        }
    }
}

// ───────────────────────── Pickers ─────────────────────────

@Composable
fun DurationPickerDialog(
    title: String,
    totalSeconds: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit,
    clearable: Boolean = false,
) {
    var minutes by rememberSaveable(title, totalSeconds) { mutableIntStateOf((totalSeconds ?: 0) / 60) }
    var seconds by rememberSaveable(title, totalSeconds) { mutableIntStateOf((totalSeconds ?: 0) % 60) }
    InkDialog(title = title, onDismiss = onDismiss, scrollable = false) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkWheel(
                value = minutes.coerceIn(0, 120),
                range = 0..120,
                label = "MIN",
                onValueChange = { minutes = it },
            )
            InkText(":", style = InkType.NumberLarge.copy(fontSize = 34.sp), color = InkColors.BoneFaint)
            InkWheel(
                value = seconds.coerceIn(0, 59),
                range = 0..59,
                label = "SEC",
                onValueChange = { seconds = it },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            // Clear fires onConfirm(null); six of eight callers ignored null,
            // which made this a button that sometimes did nothing.
            if (clearable) GhostButton("Clear", onClick = { onConfirm(null) })
            Spacer(Modifier.weight(1f))
            GhostButton("Cancel", onClick = onDismiss)
            SealButton("Set", onClick = { onConfirm((minutes * 60) + seconds) }, compact = true)
        }
    }
}

/**
 * Ink numeral wheel — the app's own dial, replacing the stock Android
 * NumberPicker that sat on the paper like a stranger. Drag the column: the
 * current value is a big serif numeral, its neighbours ghost above and below,
 * every step ticks, and the ends hard-stop (no silent wrap to zero).
 */
@Composable
fun InkWheel(
    value: Int,
    range: IntRange,
    label: String,
    onValueChange: (Int) -> Unit,
) {
    val view = LocalView.current
    val current by rememberUpdatedState(value)
    // Same stale-closure guard as WorkingNumber: the drag block outlives
    // recomposition, so it must call the current callback, not the first one.
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(InkShapes.card)
                .semantics {
                    contentDescription = label
                    stateDescription = "%02d".format(current)
                    customActions = listOf(
                        CustomAccessibilityAction("Increase") {
                            val next = (current + 1).coerceIn(range.first, range.last)
                            if (next != current) currentOnValueChange(next)
                            true
                        },
                        CustomAccessibilityAction("Decrease") {
                            val next = (current - 1).coerceIn(range.first, range.last)
                            if (next != current) currentOnValueChange(next)
                            true
                        },
                    )
                }
                .verticalStepDrag(key = "wheel:$label") { step ->
                    val next = (current + step).coerceIn(range.first, range.last)
                    if (next != current) {
                        currentOnValueChange(next)
                        Haptics.tick(view)
                    }
                }
                .padding(horizontal = InkSpacing.xl, vertical = InkSpacing.s),
        ) {
            InkText(
                if (value + 1 <= range.last) "%02d".format(value + 1) else "",
                style = InkType.NumberSmall.copy(fontSize = 17.sp),
                color = InkColors.BoneFaint.copy(alpha = 0.55f),
            )
            NumberBlock(value = "%02d".format(value), style = InkType.NumberLarge.copy(fontSize = 44.sp, lineHeight = 48.sp))
            InkText(
                if (value - 1 >= range.first) "%02d".format(value - 1) else "",
                style = InkType.NumberSmall.copy(fontSize = 17.sp),
                color = InkColors.BoneFaint.copy(alpha = 0.55f),
            )
        }
        InkText("$label · drag", style = InkType.Label, color = InkColors.BoneFaint)
    }
}

/**
 * The move wheel (R21): a form's moves on the same drag-dial as the weight
 * and reps wheels. Two neighbours ghost above and below, the current move is
 * its number and name in vermillion, every step ticks, the ends hard-stop.
 */
@Composable
fun InkMoveWheel(
    form: MartialArtsForm,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val view = LocalView.current
    val current by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val last = form.moveCount.coerceAtLeast(1)
    fun nameOf(index: Int): String = form.moves.firstOrNull { it.index == index }?.name.orEmpty()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkShapes.card)
            .semantics {
                contentDescription = "Move"
                stateDescription = "Move $current, ${nameOf(current)}"
                customActions = listOf(
                    CustomAccessibilityAction("Next move") {
                        val next = (current + 1).coerceIn(1, last)
                        if (next != current) currentOnValueChange(next)
                        true
                    },
                    CustomAccessibilityAction("Previous move") {
                        val next = (current - 1).coerceIn(1, last)
                        if (next != current) currentOnValueChange(next)
                        true
                    },
                )
            }
            .verticalStepDrag(key = "movewheel:${form.id}") { step ->
                val next = (current + step).coerceIn(1, last)
                if (next != current) {
                    currentOnValueChange(next)
                    Haptics.tick(view)
                }
            }
            .padding(vertical = InkSpacing.s),
    ) {
        listOf(2, 1).forEach { offset ->
            val i = value + offset
            InkText(
                if (i <= last) "$i  ${nameOf(i)}" else "",
                style = InkType.Body.copy(fontSize = if (offset == 1) 15.sp else 13.sp),
                color = InkColors.BoneFaint.copy(alpha = if (offset == 1) 0.6f else 0.35f),
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            NumberBlock(value = "%02d".format(value), style = InkType.NumberLarge.copy(fontSize = 34.sp, lineHeight = 38.sp))
            InkText(nameOf(value), style = InkType.Heading, color = InkColors.Vermillion, maxLines = 2)
        }
        listOf(1, 2).forEach { offset ->
            val i = value - offset
            InkText(
                if (i >= 1) "$i  ${nameOf(i)}" else "",
                style = InkType.Body.copy(fontSize = if (offset == 1) 15.sp else 13.sp),
                color = InkColors.BoneFaint.copy(alpha = if (offset == 1) 0.6f else 0.35f),
                maxLines = 1,
            )
        }
        InkText("Move · drag", style = InkType.Label, color = InkColors.BoneFaint)
    }
}

// ───────────────────────── Ink controls ─────────────────────────

/** Ink slider: hairline track, vermillion fill, a hand-pressed seal thumb. */
@Composable
fun InkSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val current by rememberUpdatedState(value)
    val span = (range.last - range.first).coerceAtLeast(1)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .pointerInput(range) {
                detectTapGestures { offset ->
                    val next = (range.first + ((offset.x / size.width).coerceIn(0f, 1f) * span).roundToInt())
                        .coerceIn(range.first, range.last)
                    if (next != current) {
                        onValueChange(next)
                        Haptics.tick(view)
                    }
                }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val next = (range.first + ((change.position.x / size.width).coerceIn(0f, 1f) * span).roundToInt())
                        .coerceIn(range.first, range.last)
                    if (next != current) {
                        onValueChange(next)
                        Haptics.tick(view)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val fraction = (value - range.first).toFloat() / span
            drawLine(InkColors.Line, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(InkColors.Vermillion, Offset(0f, cy), Offset(size.width * fraction, cy), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
            val thumb = 15.dp.toPx()
            val cx = (size.width * fraction).coerceIn(thumb / 2, size.width - thumb / 2)
            // the thumb is a seal pressed by hand — square, faintly tilted
            rotate(degrees = 6f, pivot = Offset(cx, cy)) {
                drawRoundRect(
                    color = InkColors.Vermillion,
                    topLeft = Offset(cx - thumb / 2, cy - thumb / 2),
                    size = Size(thumb, thumb),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
    }
}

/** Ink toggle: a seal thumb sliding along a hairline rail — no stock Switch. */
@Composable
fun InkToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val thumbOffset by animateDpAsState(if (checked) 24.dp else 3.dp, LiliangSprings.snappy<androidx.compose.ui.unit.Dp>().orSnap(), label = "inkToggle")
    // 48×27 visual on a 48dp-minimum touch target — the bare box sat under the
    // platform minimum and was the app's fiddliest control.
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
            ) {
                Haptics.tick(view)
                onCheckedChange(it)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 27.dp)
                .clip(InkShapes.chip)
                .background(if (checked) InkColors.Vermillion.copy(alpha = 0.18f) else Color.Transparent)
                .border(1.dp, if (checked) InkColors.Vermillion else InkColors.Line, InkShapes.chip),
        ) {
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(thumbOffset.roundToPx(), 0) }
                    .size(21.dp)
                    .clip(InkShapes.seal)
                    .background(if (checked) InkColors.Vermillion else InkColors.BoneFaint),
            )
        }
    }
}

/**
 * Search + pick from a list, with optional free entry. The options list is
 * pre-ordered by the caller; matching is a simple case-insensitive contains.
 */
@Composable
fun SearchSelectionDialog(
    title: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    allowCustomEntry: Boolean = false,
    customLabel: String = "Name",
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query, options) {
        if (query.isBlank()) options else options.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    InkDialog(title = title, onDismiss = onDismiss) {
        InkTextField(label = customLabel, value = query, onValueChange = { query = it })
        matches.take(40).forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.chip)
                    .clickable { onSelect(option) }
                    .padding(horizontal = InkSpacing.s, vertical = 9.dp),
            ) {
                InkText(option, style = InkType.BodyStrong)
            }
        }
        val trimmed = query.trim()
        if (allowCustomEntry && trimmed.isNotBlank() && matches.none { it.equals(trimmed, true) }) {
            Spacer(Modifier.height(InkSpacing.xs))
            SealButton("Use “$trimmed”", onClick = { onSelect(trimmed) }, compact = true)
        }
        if (matches.isEmpty() && !allowCustomEntry) {
            InkText("Nothing matches.", style = InkType.BodyDim, color = InkColors.BoneFaint)
        }
        Spacer(Modifier.height(InkSpacing.xs))
        GhostButton("Cancel", onClick = onDismiss)
    }
}

/** Simple dropdown-style pick: tap the pill, choose from the dialog. */
@Composable
fun InkDropdownField(
    label: String,
    selectedValue: String?,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    InkPickerPill(
        label = label,
        value = selectedValue ?: "",
        onClick = { open = true },
        modifier = modifier,
    )
    if (open) {
        SearchSelectionDialog(
            title = label,
            options = options,
            onDismiss = { open = false },
            onSelect = {
                onSelect(it)
                open = false
            },
        )
    }
}

// ───────────────────────── Martial-arts pickers ─────────────────────────

/**
 * Picker for a round's content: forms first (套路 — what the hall manages),
 * then combos, one search across both.
 */
@Composable
fun MartialArtsSelectionDialog(
    forms: List<MartialArtsForm>,
    combos: List<ComboItem>,
    onDismiss: () -> Unit,
    onPickForm: (MartialArtsForm) -> Unit,
    onPickCombo: (ComboItem) -> Unit,
    title: String = "Form or combo",
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matchingForms = forms.filter {
        query.isBlank() || it.title.contains(query, true) ||
            it.displayShortTitle.contains(query, true) || it.discipline.contains(query, true)
    }
    val matchingCombos = combos.filter {
        query.isBlank() || it.name.contains(query, true) || it.discipline.contains(query, true)
    }
    InkDialog(title = title, onDismiss = onDismiss) {
        InkTextField(label = "Search", value = query, onValueChange = { query = it })
        if (matchingForms.isNotEmpty()) {
            InkText("FORMS · 套路", style = InkType.Label, color = InkColors.Vermillion)
            matchingForms.forEach { form ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InkShapes.chip)
                        .border(1.dp, InkColors.Vermillion.copy(alpha = 0.35f), InkShapes.chip)
                        .clickable { onPickForm(form) }
                        .padding(horizontal = InkSpacing.m, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        InkText(form.displayShortTitle, style = InkType.BodyStrong)
                        val sub = listOf(form.title, form.discipline)
                            .filter { it.isNotBlank() && it != form.displayShortTitle }
                            .joinToString(" · ")
                        if (sub.isNotBlank()) {
                            InkText(sub, style = InkType.Label.copy(letterSpacing = 0.4.sp), color = InkColors.BoneFaint)
                        }
                    }
                    if (form.moveCount > 0) {
                        InkText(pluralize(form.moveCount, "move"), style = InkType.Label, color = InkColors.BoneFaint)
                    }
                }
            }
        }
        if (matchingCombos.isNotEmpty()) {
            InkText("COMBOS", style = InkType.Label, color = InkColors.BoneDim)
            matchingCombos.forEach { combo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(InkShapes.chip)
                        .border(1.dp, InkColors.Line, InkShapes.chip)
                        .clickable { onPickCombo(combo) }
                        .padding(horizontal = InkSpacing.m, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InkText(combo.name, style = InkType.BodyStrong, modifier = Modifier.weight(1f))
                    if (combo.discipline.isNotBlank()) {
                        InkText(combo.discipline, style = InkType.Label, color = InkColors.BoneFaint)
                    }
                }
            }
        }
        if (matchingForms.isEmpty() && matchingCombos.isEmpty()) {
            InkText(
                "Nothing matches — forms live in the Hall, combos in the Library.",
                style = InkType.BodyDim,
                color = InkColors.BoneFaint,
            )
        }
        GhostButton("Cancel", onClick = onDismiss)
    }
}

/** "Practised up to move N" — slider with the live move name. */
@Composable
fun FormMovePickerDialog(
    form: MartialArtsForm,
    initialIndex: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit,
) {
    var moveIndex by rememberSaveable(form.id) {
        mutableIntStateOf(initialIndex ?: form.moveCount)
    }
    val move = form.moves.firstOrNull { it.index == moveIndex }
    InkDialog(title = "How far through ${form.displayShortTitle}?", onDismiss = onDismiss, scrollable = false) {
        InkText("Move $moveIndex of ${form.moveCount}", style = InkType.Body, color = InkColors.BoneDim)
        if (form.moveCount < 2) {
            move?.let { InkText(it.name, style = InkType.Heading, color = InkColors.Vermillion) }
        }
        if (form.moveCount >= 2) {
            // R21: the move names on a wheel, dragged like the weight and reps
            // dials; the slider below stays for long jumps through big forms.
            InkMoveWheel(form = form, value = moveIndex.coerceIn(1, form.moveCount), onValueChange = { moveIndex = it })
            InkSlider(
                value = moveIndex.coerceIn(1, form.moveCount),
                range = 1..form.moveCount,
                onValueChange = { moveIndex = it },
            )
        }
        InkText(
            "Stamped into the Hall as a checkpoint when the workout is sealed.",
            style = InkType.Label.copy(letterSpacing = 0.4.sp),
            color = InkColors.BoneFaint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
            GhostButton("Clear", onClick = { onConfirm(null) })
            Spacer(Modifier.weight(1f))
            GhostButton("Cancel", onClick = onDismiss)
            SealButton("Set", onClick = { onConfirm(moveIndex) }, compact = true)
        }
    }
}

// ───────────────────────── Shared helpers ─────────────────────────

/** "14 Jun 2026" — compact date for beams and row subtitles. */
fun formatShortDate(date: String): String = runCatching {
    java.time.LocalDate.parse(date)
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
}.getOrDefault(date)

/** "1 exercise" / "3 exercises" — never "1 exercises" anywhere in the app. */
fun pluralize(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"

/** "Thursday 12 June 2026", or the raw string if it won't parse. */
fun formatLongDate(date: String): String =
    runCatching { java.time.LocalDate.parse(date) }
        .map {
            "${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::titlecase)} ${it.dayOfMonth} " +
                "${it.month.name.lowercase().replaceFirstChar(Char::titlecase)} ${it.year}"
        }
        .getOrElse { date }

fun parseEditableDecimal(raw: String): Double? = parseLeadingDouble(raw.replace(',', '.'))

/** True when this exercise logs time/distance rather than weight×reps. */
fun ExerciseCatalogItem?.isTimedOrDistance(): Boolean {
    val category = this?.defaultCategory?.trim()?.lowercase().orEmpty()
    return this?.logFormat != LogFormat.REPS || category == "running" || category == "martial arts"
}

/** Most-used exercises first (top 12), then everything alphabetical. */
fun orderedExerciseOptions(exercises: List<ExerciseCatalogItem>): List<String> {
    val top = exercises
        .filter { it.usageCount > 0 }
        .sortedWith(compareByDescending<ExerciseCatalogItem> { it.usageCount }.thenBy { it.name })
        .take(12)
    val topNames = top.map { it.name }.toSet()
    val rest = exercises.filterNot { it.name in topNames }.sortedBy { it.name.lowercase() }
    return (top + rest).map { it.name }
}

/** Exercise options, then any muscle-group slots not already named. */
fun orderedExerciseOrMuscleOptions(
    exercises: List<ExerciseCatalogItem>,
    muscleGroups: List<String>,
): List<String> {
    val exerciseNames = orderedExerciseOptions(exercises)
    val seen = exerciseNames.map { it.lowercase() }.toMutableSet()
    val muscles = muscleGroups.filter { it.lowercase() !in seen }.sortedBy { it.lowercase() }
    return exerciseNames + muscles
}

fun editableDouble(value: Double?): String = when {
    value == null -> ""
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> value.toString()
}

/** Short readout for a historical set — the pill text. */
fun shortSetSummary(set: WorkoutSetRecord, unit: WeightUnit = WeightUnit.KG): String = when {
    set.weight != null && !set.repsText.isNullOrBlank() ->
        "${formatWeight(set.weight, unit)} × ${set.repsText}"
    set.weight != null -> formatWeight(set.weight, unit)
    set.distanceKm != null && set.durationSeconds != null ->
        "${formatDistance(set.distanceKm)} · ${formatDuration(set.durationSeconds)}"
    set.distanceKm != null -> formatDistance(set.distanceKm)
    set.durationSeconds != null -> formatDuration(set.durationSeconds)
    !set.repsText.isNullOrBlank() -> "${set.repsText} reps"
    else -> "—"
}

/**
 * Distance / duration / pace are mutually determined; recompute the
 * dependent one from whichever pair the user just completed.
 */
fun updateRunningSet(
    current: WorkoutSessionSet,
    durationSeconds: Int? = current.durationSeconds,
    distanceKm: Double? = current.distanceKm,
    paceSecondsPerKm: Double? = current.paceSecondsPerKm,
    changedField: String,
): WorkoutSessionSet {
    var nextDuration = durationSeconds
    var nextDistance = distanceKm
    var nextPace = paceSecondsPerKm

    when (changedField) {
        "duration", "distance" -> {
            if (nextDuration != null && nextDistance != null && nextDistance > 0.0) {
                nextPace = nextDuration / nextDistance
            } else if (changedField == "duration" && nextDuration != null && nextPace != null && nextPace > 0.0) {
                nextDistance = nextDuration / nextPace
            } else if (changedField == "distance" && nextDistance != null && nextDistance > 0.0 && nextPace != null) {
                nextDuration = (nextDistance * nextPace).roundToInt()
            }
        }
        "pace" -> {
            if (nextDuration != null && nextPace != null && nextPace > 0.0) {
                nextDistance = nextDuration / nextPace
            } else if (nextDistance != null && nextDistance > 0.0 && nextPace != null) {
                nextDuration = (nextDistance * nextPace).roundToInt()
            }
        }
    }

    return current.copy(
        durationSeconds = nextDuration,
        distanceKm = nextDistance,
        paceSecondsPerKm = nextPace,
    )
}

// ───────────────────────── Carved wood ─────────────────────────

/** A small carved wooden tag — the selection chip of the hanging-menu
 *  surfaces. Selected tags catch gold at the rim. */
/** Two slim up/down nudges for builder rows — reordering without drag ceremony. */
@Composable
fun ReorderHandles(canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    val view = LocalView.current
    Row {
        Box(
            Modifier
                .size(width = 44.dp, height = 48.dp)
                .clip(InkShapes.chip)
                .semantics { contentDescription = "Move up" }
                .clickable(enabled = canUp) { com.liliang.app.platform.Haptics.tick(view); onUp() },
            contentAlignment = Alignment.Center,
        ) { InkText("\u2191", style = InkType.Body, color = if (canUp) InkColors.BoneDim else InkColors.BoneFaint.copy(alpha = 0.35f)) }
        Box(
            Modifier
                .size(width = 44.dp, height = 48.dp)
                .clip(InkShapes.chip)
                .semantics { contentDescription = "Move down" }
                .clickable(enabled = canDown) { com.liliang.app.platform.Haptics.tick(view); onDown() },
            contentAlignment = Alignment.Center,
        ) { InkText("\u2193", style = InkType.Body, color = if (canDown) InkColors.BoneDim else InkColors.BoneFaint.copy(alpha = 0.35f)) }
    }
}

/** Height of the dock bar itself — shared with LiliangApp's InkDock. */
val DockBarHeight = 58.dp

/**
 * Bottom padding for a scrolling tab room, so the last row clears the floating
 * dock and whatever navigation bar the device draws — tracks gesture-nav insets
 * instead of hard-coding them.
 */
@Composable
fun dockClearance(): androidx.compose.ui.unit.Dp {
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()
    return nav + DockBarHeight + InkSpacing.m * 2 + InkSpacing.s
}

/**
 * Bottom padding for a dockless overlay room, so the last row — usually the
 * Save/Delete action — clears whatever navigation bar the device draws. The
 * rooms used to hard-code 48dp flat, which put that row under a 3-button bar.
 */
@Composable
fun overlayClearance(): androidx.compose.ui.unit.Dp {
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()
    return nav + InkSpacing.hero
}

/**
 * The one editor masthead: back, title, optional label subtitle, optional trailing
 * action seal, optional extra rows, then the brush divider — seeded from the title
 * so the same room always opens with the same stroke.
 */
@Composable
fun EditorHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    subtitleColor: Color = InkColors.BoneFaint,
    brushHanzi: String = "",
    action: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(Modifier.statusBarsPadding()) {
        GhostButton("\u2039 Back", onClick = onBack)
        Spacer(Modifier.height(InkSpacing.m))
        val titleBlock: @Composable ColumnScope.() -> Unit = {
            InkText(title, style = InkType.Masthead)
            if (subtitle.isNotBlank()) InkText(subtitle, style = InkType.Label, color = subtitleColor)
            if (brushHanzi.isNotBlank()) InkText(brushHanzi, style = com.liliang.app.design.BrushHanzi(18.sp), color = InkColors.Vermillion)
        }
        if (action != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { titleBlock() }
                action()
            }
        } else {
            titleBlock()
        }
        if (content != null) {
            Spacer(Modifier.height(InkSpacing.xs))
            content()
        }
        Spacer(Modifier.height(InkSpacing.s))
        BrushDivider(seed = title.hashCode())
    }
}

/**
 * The one list row. Light rows share a single treatment (chip shape, hairline
 * border); prominent rows are card-weight with raised paper and a chevron.
 */
@Composable
fun InkListRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String = "",
    hanzi: String = "",
    prominent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val view = LocalView.current
    val shape = if (prominent) InkShapes.card else InkShapes.chip
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (prominent) Modifier.background(InkColors.PaperRaised) else Modifier)
            .border(1.dp, InkColors.Line, shape)
            .clickable { com.liliang.app.platform.Haptics.tick(view); onClick() }
            .padding(if (prominent) InkSpacing.l else InkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hanzi.isNotBlank()) {
            Stamp(hanzi, tint = InkColors.BoneDim, size = if (prominent) 36.dp else 28.dp)
            Spacer(Modifier.width(InkSpacing.m))
        }
        Column(Modifier.weight(1f)) {
            InkText(title, style = if (prominent) InkType.Heading else InkType.BodyStrong, maxLines = 1)
            if (subtitle.isNotBlank()) InkText(subtitle, style = InkType.Label, color = InkColors.BoneFaint, maxLines = 1)
        }
        when {
            trailing != null -> trailing()
            prominent -> InkText("\u2192", style = InkType.Body, color = InkColors.BoneFaint)
        }
    }
}

@Composable
fun WoodTag(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = InkColors.Gold,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .clip(InkShapes.card)
            .background(com.liliang.app.design.InkWood.Plaque)
            .woodGrain(seed = text.hashCode())
            .border(1.dp, if (selected) accent else InkColors.WoodEdge, InkShapes.card)
            .semantics { this.selected = selected }
            .clickable {
                Haptics.tick(view)
                onClick()
            }
            .padding(horizontal = InkSpacing.m, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        InkText(
            text,
            style = InkType.Body,
            color = if (selected) accent else InkColors.Bone,
            maxLines = 1,
        )
    }
}

// ───────────────────────── Working numerals ─────────────────────────

/** A big numeral you set by dragging up/down — or tap to type it exactly
 *  (81 kg is real; the plates don't care about your 2.5 kg step). */
@Composable
fun WorkingNumber(
    label: String,
    value: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onStep: (Int) -> Unit,
) {
    // The drag's pointerInput is keyed on the label and outlives recomposition,
    // so it must read the CURRENT onStep — the first-composition closure kept
    // writing a removed exercise's orphaned state (haptics ticked, numeral
    // frozen) when the next item slid into the same pager slot.
    val currentOnStep by rememberUpdatedState(onStep)
    // The whole column is the drag target — a generous swipe area, no +/- needed.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(InkShapes.card)
            .semantics {
                contentDescription = label
                stateDescription = value
                customActions = listOf(
                    CustomAccessibilityAction("Increase") { currentOnStep(1); true },
                    CustomAccessibilityAction("Decrease") { currentOnStep(-1); true },
                )
            }
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .verticalStepDrag(key = label) { currentOnStep(it) }
            .padding(vertical = InkSpacing.s),
    ) {
        InkText(label, style = InkType.Caption, color = InkColors.BoneFaint)
        NumberBlock(value = value, style = InkType.NumberHuge.copy(fontSize = fontSize, lineHeight = fontSize))
        InkText(
            if (onTap != null) "drag ▲▼ · tap to type" else "drag ▲▼",
            style = InkType.LabelSmall,
            color = InkColors.BoneFaint.copy(alpha = 0.6f),
        )
    }
}

/** Ink numeric entry — the exact-value companion to the drag dials. */
@Composable
fun NumberEntryDialog(
    title: String,
    initial: String,
    decimal: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    // Opens focused with the seeded value selected — type 81, done. The old
    // field needed a tap, a select-all and a delete before the number.
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val text = field.text
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    InkDialog(title = title, onDismiss = onDismiss) {
        FieldShell(label = if (decimal) "Value" else "Whole number") {
            BasicTextField(
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                value = field,
                onValueChange = { raw ->
                    field = raw.copy(text = raw.text.filter { it.isDigit() || it == '.' || it == ',' })
                },
                singleLine = true,
                textStyle = InkType.NumberSmall.copy(fontSize = 22.sp, color = InkColors.Bone),
                cursorBrush = SolidColor(InkColors.Vermillion),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            SealButton(
                "Set",
                hanzi = "定",
                enabled = parseLeadingDouble(text) != null,
                onClick = { parseLeadingDouble(text)?.let(onConfirm) },
            )
            GhostButton("Cancel", onClick = onDismiss)
        }
    }
}

fun trimWeight(v: Double): String =
    if (kotlin.math.abs(v - v.roundToInt()) < 0.005) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

// ───────────────────────── Streak math ─────────────────────────
// One streak definition for the whole app (Today's lantern, the Scroll hero):
// the flame survives up to two consecutive rest days; a third puts it out.
// Days the active plan itself designates as rest are NEUTRAL — being on-plan
// must never extinguish the flame.

/**
 * Weekdays the active plan always rests. Only weekdays that are REST in every
 * week of the plan qualify (a weekday that alternates work/rest still counts
 * against the flame when skipped).
 */
internal fun planRestWeekdays(plans: List<com.liliang.app.data.WorkoutPlan>): Set<java.time.DayOfWeek> {
    val plan = plans.firstOrNull { it.isActive } ?: return emptySet()
    if (plan.days.isEmpty()) return emptySet()
    // Plan dayIndex 0 is MONDAY by definition (see recommendationFor) — not
    // relative to the start date, or a mid-week block start would shift every
    // rest day the streak lantern forgives.
    return (0..6).filter { dayIndex ->
        val slots = plan.days.filter { it.dayIndex == dayIndex }
        slots.isNotEmpty() && slots.all { it.dayType == com.liliang.app.data.PlanDayType.REST }
    }.map { java.time.DayOfWeek.MONDAY.plus(it.toLong()) }.toSet()
}

/**
 * The forgiving streak: how many training days deep the current run is.
 * Walking backward from today — trained days count and reset the break;
 * plan-rest weekdays are neutral; any other missed day burns one of two
 * allowed break days, and a third consecutive one ends the run (returns 0
 * if that happens before any trained day is found).
 */
internal fun computeForgivingStreak(
    history: Map<String, Int>,
    restWeekdays: Set<java.time.DayOfWeek> = emptySet(),
): Int {
    var day = java.time.LocalDate.now()
    var count = 0
    var rest = 0
    var guard = 0
    while (guard < 1200) {
        val trained = (history[day.toString()] ?: 0) > 0
        when {
            trained -> { count++; rest = 0 }
            day.dayOfWeek in restWeekdays -> Unit // neutral — neither feeds nor extinguishes
            else -> { rest++; if (rest >= 3) break }
        }
        day = day.minusDays(1)
        guard++
    }
    return count
}

/** Consecutive non-neutral rest days ending today (the flame's fuel warning), capped at 3. */
internal fun restDaysBanked(
    history: Map<String, Int>,
    restWeekdays: Set<java.time.DayOfWeek> = emptySet(),
): Int {
    var day = java.time.LocalDate.now()
    var banked = 0
    var guard = 0
    while (guard < 14) {
        if ((history[day.toString()] ?: 0) > 0) break
        if (day.dayOfWeek !in restWeekdays) {
            banked++
            if (banked >= 3) break
        }
        day = day.minusDays(1)
        guard++
    }
    return banked
}
