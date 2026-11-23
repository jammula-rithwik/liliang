package com.liliang.app.data

import kotlin.math.roundToInt

// Pure parsing helpers that recover numeric values from free-form text fields.
// These are intentionally permissive: the workout DB has many years of human-entered
// strings ("8", "8-10", "8 reps", "12,5 kg"), and the parsers extract the leading
// numeric token rather than rejecting non-canonical input.

/**
 * Extract the first numeric token from [raw] as a [Double]. Accepts both `,` and `.` as
 * the decimal separator and tolerates a leading minus. Returns null when [raw] is null,
 * blank, or contains no numeric token at all.
 */
fun parseLeadingDouble(raw: String?): Double? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().replace(',', '.')
    val match = Regex("""-?\d+(?:\.\d+)?""").find(normalized) ?: return null
    return match.value.toDoubleOrNull()
}

/**
 * Extract the first numeric token from [raw] as an [Int], rounded from a [Double] so
 * that "8.4 reps" yields `8` rather than failing. Returns null on the same conditions
 * as [parseLeadingDouble].
 */
fun parseLeadingInt(raw: String?): Int? = parseLeadingDouble(raw)?.roundToInt()
