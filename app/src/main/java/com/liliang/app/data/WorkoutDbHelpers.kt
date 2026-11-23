package com.liliang.app.data

import android.database.Cursor
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

// Shared helpers used by every part of the data layer that touches a Cursor or a
// JSONObject. They were previously file-private helpers at the bottom of
// WorkoutRepository.kt; lifting them to module-internal visibility lets the future
// per-aggregate service classes (Phase 2.B2 in the improvement plan) reuse them
// without duplicating the cursor-decoding boilerplate.
//
// All functions are intentionally `internal` rather than `public` — they're
// implementation details of the data layer, not API for the rest of the app.

internal fun Cursor.columnIndex(name: String): Int = getColumnIndexOrThrow(name)

/** Read a non-null TEXT column, returning the empty string when SQLite gave back null. */
internal fun Cursor.string(name: String): String = getString(columnIndex(name)).orEmpty()

/**
 * Best-effort coercion of a column to its string-form, normalising integers and floats
 * that have been stored under a TEXT column. Returns null only when the column itself
 * is SQL NULL. Floats are rendered with up to two decimals; integers omit the decimal.
 */
internal fun Cursor.anyString(name: String): String? {
    val index = columnIndex(name)
    if (isNull(index)) return null
    return when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index).toString()
        Cursor.FIELD_TYPE_FLOAT -> {
            val value = getDouble(index)
            if (value % 1.0 == 0.0) value.roundToInt().toString() else String.format(Locale.US, "%.2f", value)
        }
        else -> getString(index)
    }
}

internal fun Cursor.long(name: String): Long = getLong(columnIndex(name))

internal fun Cursor.int(name: String): Int = getInt(columnIndex(name))

/**
 * Read a column as Int while tolerating mixed storage types: float columns get rounded,
 * text columns are passed through [parseLeadingInt] so that strings like "8 reps" or
 * "10-12" still yield a usable number. Returns null when the column is SQL NULL.
 */
internal fun Cursor.intOrNull(name: String): Int? {
    val index = columnIndex(name)
    if (isNull(index)) return null
    return when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getInt(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index).roundToInt()
        else -> parseLeadingInt(getString(index))
    }
}

internal fun Cursor.longOrNull(name: String): Long? {
    val index = columnIndex(name)
    return if (isNull(index)) null else getLong(index)
}

/**
 * Read a column as Double while tolerating mixed storage types: text columns are
 * parsed via [parseLeadingDouble] so legacy free-form values still produce a number.
 * Returns null when the column is SQL NULL.
 */
internal fun Cursor.doubleOrNull(name: String): Double? {
    val index = columnIndex(name)
    if (isNull(index)) return null
    return when (getType(index)) {
        Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_INTEGER -> getDouble(index)
        else -> parseLeadingDouble(getString(index))
    }
}

/**
 * Read an integer field from JSON that may be either absent or explicitly null. The
 * built-in `optInt` collapses both cases to 0; this helper distinguishes them so we
 * can preserve "unset" semantics through the AI-import path.
 */
internal fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

/**
 * Read a double field from JSON that may be either absent or explicitly null. See
 * [optNullableInt] for the rationale.
 */
internal fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)
