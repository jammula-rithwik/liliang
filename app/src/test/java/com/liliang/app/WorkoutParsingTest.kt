package com.liliang.app

import com.liliang.app.data.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutParsingTest {

    @Test
    fun parsesLeadingNumberFromTextReps() {
        assertEquals(12, parseLeadingInt("12q"))
        assertEquals(8, parseLeadingInt("8 - 8"))
    }

    @Test
    fun returnsNullWhenNoNumberExists() {
        assertNull(parseLeadingDouble("abc"))
        assertNull(parseLeadingInt(""))
    }

    @Test
    fun formatsDurationAndPace() {
        assertEquals("5m 30s", formatDuration(330))
        assertEquals("5:00 /km", formatPace(300.0))
    }

    // ── F1 (6): the formatter/parser edges the UI leans on ──

    @Test
    fun `null formats read as em dashes, hours read as hours`() {
        assertEquals("\u2014", formatDuration(null))
        assertEquals("\u2014", formatDistance(null))
        assertEquals("1h 05m 30s", formatDuration(3930))
    }

    @Test
    fun `distances trim their noise`() {
        assertEquals("5 km", formatDistance(5.0))
        assertEquals("5.2 km", formatDistance(5.2))
    }

    @Test
    fun `comma decimals parse like dotted ones`() {
        assertEquals(81.5, parseLeadingDouble("81,5")!!, 0.0001)
    }

    // ── R19: pound stacks. Storage stays kilograms; only the reading changes ──

    @Test
    fun `a pound reading survives the round trip to storage and back`() {
        // The case that prompted this: a leg curl stack marked 50, in pounds.
        val storedKg = WeightUnit.LB.toKg(50.0)
        assertEquals(22.6796, storedKg, 0.0001)
        assertEquals(50.0, WeightUnit.LB.fromKg(storedKg), 0.0001)
        assertEquals("50 lb", formatWeight(storedKg, WeightUnit.LB))
        // And the same row read as what it actually is.
        assertEquals("22.7 kg", formatWeight(storedKg, WeightUnit.KG))
    }

    @Test
    fun `kilograms are the default, so every existing caller is unchanged`() {
        assertEquals("80 kg", formatWeight(80.0))
        assertEquals("82.5 kg", formatWeight(82.5))
        assertEquals("\u2014", formatWeight(null))
        assertEquals(47.5, WeightUnit.KG.toKg(47.5), 0.0)
    }

    @Test
    fun `an unknown or missing unit column reads as kilograms`() {
        assertEquals(WeightUnit.KG, WeightUnit.fromDb(null))
        assertEquals(WeightUnit.KG, WeightUnit.fromDb(""))
        assertEquals(WeightUnit.KG, WeightUnit.fromDb("stones"))
        assertEquals(WeightUnit.LB, WeightUnit.fromDb("lb"))
    }
}
