package com.liliang.app

import com.liliang.app.data.DayActivity
import com.liliang.app.data.SealCatalog
import com.liliang.app.data.SealStats
import com.liliang.app.data.SealStatsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SealsTest {

    @Test
    fun catalogueIdsAndGlyphsAreUnique() {
        val ids = SealCatalog.seals.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val glyphs = SealCatalog.seals.map { it.hanzi }
        assertEquals("one carved glyph per seal", glyphs.size, glyphs.toSet().size)
        SealCatalog.seals.forEach { assertEquals("${it.id} carves one character", 1, it.hanzi.codePointCount(0, it.hanzi.length)) }
    }

    @Test
    fun emptyHistoryEarnsNothing() {
        assertTrue(SealCatalog.newlyEarned(SealStats(), emptyMap()).isEmpty())
        SealCatalog.evaluate(SealStats(), emptyMap()).forEach {
            assertFalse(it.earned)
            assertEquals(0f, it.progress, 0f)
        }
    }

    @Test
    fun thresholdsAreInclusive() {
        val earned = SealCatalog.newlyEarned(SealStats(trainedDays = 1, streak = 7), emptyMap()).map { it.id }
        assertTrue("first-step" in earned)
        assertTrue("seven-lanterns" in earned)
        assertFalse("full-moon" in earned)
    }

    @Test
    fun awardedSealsAreNotAwardedAgainAndStayEarned() {
        val awards = mapOf("seven-lanterns" to "2026-11-01")
        // The streak has since broken; the seal stays earned.
        val states = SealCatalog.evaluate(SealStats(streak = 0), awards)
        val lanterns = states.first { it.def.id == "seven-lanterns" }
        assertTrue(lanterns.earned)
        assertEquals(1f, lanterns.progress, 0f)
        assertFalse(SealCatalog.newlyEarned(SealStats(streak = 9), awards).any { it.id == "seven-lanterns" })
    }

    @Test
    fun progressIsAFractionOfTheTarget() {
        val st = SealCatalog.evaluate(SealStats(longestRunKm = 5.0), emptyMap()).first { it.def.id == "wind-runner" }
        assertEquals(0.5f, st.progress, 0.001f)
        val over = SealCatalog.evaluate(SealStats(heaviestLiftKg = 300.0), emptyMap()).first { it.def.id == "mountain" }
        assertEquals(1f, over.progress, 0f)
    }

    @Test
    fun attendanceCountsTheLast30DaysAndTheThreeHarmonies() {
        val today = LocalDate.of(2026, 11, 23)
        val history = mapOf(
            "2026-11-23" to 1,
            "2026-11-20" to 2,
            "2026-10-25" to 1, // 29 days back — inside
            "2026-10-24" to 1, // 30 days back — outside
            "2026-01-01" to 0, // zero rows never count
        )
        val activity = mapOf(
            "2026-11-23" to DayActivity(hasLift = true),
            "2026-11-20" to DayActivity(hasRun = true, hasMartialArts = true),
        )
        val (days, last30, harmonies) = SealStatsBuilder.attendance(history, activity, today)
        assertEquals(4, days)
        assertEquals(3, last30)
        assertTrue(harmonies)

        val (_, _, noRun) = SealStatsBuilder.attendance(history, mapOf("2026-11-23" to DayActivity(hasLift = true)), today)
        assertFalse(noRun)
    }
}
