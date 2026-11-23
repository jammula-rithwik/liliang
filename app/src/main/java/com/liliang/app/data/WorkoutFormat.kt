package com.liliang.app.data

import kotlin.math.roundToInt

// Pure presentation helpers that turn numeric values into user-facing strings.
// These are referenced from both the UI layer (WorkoutApp.kt) and from the
// repository's KPI summarisation (e.g. PerformanceHint formatting), so they live
// next to the domain models rather than in either layer.

/**
 * Render a stored weight — always kilograms — in [unit]. Whole numbers drop the
 * decimal ("80 kg"), fractions show one ("82.5 kg"). Em dash for null.
 * The default is KG, so every caller that has no exercise in hand is unchanged.
 */
fun formatWeight(weight: Double?, unit: WeightUnit = WeightUnit.KG): String {
    if (weight == null) return "\u2014"
    val shown = unit.fromKg(weight)
    val suffix = if (unit == WeightUnit.LB) "lb" else "kg"
    // Round before testing for wholeness: 50 lb stores as 22.6796 kg and comes
    // back as 49.99999, which must still read "50 lb" and not "50.0 lb".
    val rounded = kotlin.math.round(shown * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.roundToInt()} $suffix" else "${"%.1f".format(rounded)} $suffix"
}

/**
 * Render a distance in kilometres, trimming noise: whole numbers plain ("5 km"),
 * fractions up to two decimals without trailing zeros ("5.2 km", "5.25 km").
 * Returns an em dash for null.
 */
fun formatDistance(distanceKm: Double?): String = when {
    distanceKm == null -> "\u2014"
    distanceKm % 1.0 == 0.0 -> "${distanceKm.roundToInt()} km"
    else -> "${"%.2f".format(distanceKm).trimEnd('0').trimEnd('.')} km"
}

/**
 * Render a duration in seconds, choosing the most compact unit:
 *  - hours present: "1h 02m 03s"
 *  - minutes only:  "5m 04s"
 *  - sub-minute:    "42s"
 * Returns an em dash for null.
 */
fun formatDuration(totalSeconds: Int?): String {
    if (totalSeconds == null) return "\u2014"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm %02ds".format(hours, minutes, seconds)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "${seconds}s"
    }
}

/**
 * Render a running pace as "M:SS /km". Returns an em dash for null.
 */
fun formatPace(secondsPerKm: Double?): String {
    if (secondsPerKm == null) return "\u2014"
    val rounded = secondsPerKm.roundToInt()
    val minutes = rounded / 60
    val seconds = rounded % 60
    return "%d:%02d /km".format(minutes, seconds)
}

/**
 * Render a logged set as a bullet-separated summary of all the dimensions actually
 * recorded for it (weight, reps, duration, distance, pace, rest, RPE). Empty fields
 * are omitted; an entirely empty set falls back to "No data".
 */
fun formatSetRecord(set: WorkoutSetRecord, unit: WeightUnit = WeightUnit.KG): String {
    val parts = buildList {
        if (set.weight != null) add(formatWeight(set.weight, unit))
        if (!set.repsText.isNullOrBlank()) add("${set.repsText} reps")
        if (set.durationSeconds != null) add(formatDuration(set.durationSeconds))
        if (set.distanceKm != null) add(formatDistance(set.distanceKm))
        if (set.paceSecondsPerKm != null) add(formatPace(set.paceSecondsPerKm))
        if (set.restSeconds != null) add("rest ${formatDuration(set.restSeconds)}")
        if (set.rpe != null) add("RPE ${set.rpe}")
    }
    return parts.joinToString(" • ").ifBlank { "No data" }
}

/**
 * Convenience formatter for the in-progress session-set type, which carries the same
 * dimensions as a stored [WorkoutSetRecord] under slightly different field names.
 * Adapts the session-set fields to the record shape and delegates.
 */
fun formatSessionSet(set: WorkoutSessionSet): String = formatSetRecord(
    WorkoutSetRecord(
        id = set.id,
        weight = set.weight,
        repsText = set.repsText,
        restSeconds = set.restSeconds,
        durationSeconds = set.durationSeconds,
        distanceKm = set.distanceKm,
        paceSecondsPerKm = set.paceSecondsPerKm,
        rpe = set.rpe,
        notes = set.notes,
    ),
)
