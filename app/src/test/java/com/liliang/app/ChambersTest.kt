package com.liliang.app

import com.liliang.app.data.ChamberCatalog
import com.liliang.app.data.ChamberPace
import com.liliang.app.data.ChamberProgress
import com.liliang.app.data.ChamberState
import com.liliang.app.data.chineseNumeral
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChambersTest {
    @Test
    fun thirtySixChambersNumberedOnce() {
        val numbers = ChamberCatalog.chambers.map { it.number }
        assertEquals((1..36).toList(), numbers.sorted())
        assertEquals(36, numbers.toSet().size)
    }

    @Test
    fun threeChambersInEverySeasonAndEverySeasonExists() {
        assertEquals(12, ChamberCatalog.seasons.size)
        ChamberCatalog.seasons.forEach { season ->
            assertEquals("season ${season.code}", 3, ChamberCatalog.chambersIn(season.code).size)
        }
        ChamberCatalog.chambers.forEach { assertTrue(ChamberCatalog.season(it.seasonCode) != null) }
    }

    @Test
    fun seasonsAreContiguousThirteenWeekBlocksStartingOnMondays() {
        val seasons = ChamberCatalog.seasons
        assertEquals(LocalDate.of(2026, 9, 7), seasons.first().start)
        assertEquals(LocalDate.of(2029, 9, 2), seasons.last().end)
        seasons.forEach { assertEquals(DayOfWeek.MONDAY, it.start.dayOfWeek) }
        seasons.zipWithNext().forEach { (a, b) -> assertEquals(a.end.plusDays(1), b.start) }
        assertEquals(LocalDate.of(2026, 11, 30), seasons.first().gateStart)
    }

    @Test
    fun beforeThePathTheFirstSeasonIsUpcoming() {
        val today = LocalDate.of(2026, 9, 1)
        assertEquals("S2", ChamberCatalog.currentSeason(today).code)
        val first = ChamberCatalog.chamber(1)!!
        assertEquals(ChamberState.UPCOMING, ChamberCatalog.stateOf(first, passed = false, today = today))
        assertEquals(ChamberState.LOCKED, ChamberCatalog.stateOf(ChamberCatalog.chamber(4)!!, passed = false, today = today))
        assertEquals(ChamberState.PASSED, ChamberCatalog.stateOf(ChamberCatalog.chamber(36)!!, passed = true, today = today))
    }

    @Test
    fun inSeasonChambersAreCurrentAndPastOnesOpen() {
        val today = LocalDate.of(2027, 1, 10)
        assertEquals("S3", ChamberCatalog.currentSeason(today).code)
        assertEquals(ChamberState.CURRENT, ChamberCatalog.stateOf(ChamberCatalog.chamber(5)!!, passed = false, today = today))
        assertEquals(ChamberState.OPEN, ChamberCatalog.stateOf(ChamberCatalog.chamber(1)!!, passed = false, today = today))
    }

    @Test
    fun weekAndClockFollowTheSeason() {
        val s2 = ChamberCatalog.seasons.first()
        assertEquals(0, s2.weekOf(LocalDate.of(2026, 9, 4)))
        assertEquals(1, s2.weekOf(LocalDate.of(2026, 9, 7)))
        assertEquals(12, s2.weekOf(LocalDate.of(2026, 11, 29)))
        assertEquals(13, s2.weekOf(LocalDate.of(2026, 11, 30)))
        assertEquals(0f, s2.timeFraction(LocalDate.of(2026, 8, 1)), 0.0001f)
        assertEquals(1f, s2.timeFraction(LocalDate.of(2026, 12, 5)), 0.0001f)
    }

    @Test
    fun paceReadsAgainstTheClock() {
        val s2 = ChamberCatalog.seasons.first()
        val halfway = s2.start.plusWeeks(6)
        assertEquals(ChamberPace.ON_TRACK, ChamberCatalog.pace(ChamberProgress(29.0, 58.0), s2, halfway))
        assertEquals(ChamberPace.BEHIND, ChamberCatalog.pace(ChamberProgress(5.0, 58.0), s2, halfway))
        assertEquals(ChamberPace.AHEAD, ChamberCatalog.pace(ChamberProgress(50.0, 58.0), s2, halfway))
        assertEquals(ChamberPace.READY, ChamberCatalog.pace(ChamberProgress(58.0, 58.0), s2, halfway))
        assertEquals(null, ChamberCatalog.pace(ChamberProgress(), s2, halfway))
    }

    @Test
    fun chineseNumeralsReadNaturally() {
        assertEquals("一", chineseNumeral(1))
        assertEquals("十", chineseNumeral(10))
        assertEquals("十四", chineseNumeral(14))
        assertEquals("二十", chineseNumeral(20))
        assertEquals("三十六", chineseNumeral(36))
    }

    @Test
    fun formattedHoldsReadAsMinutesAndSeconds() {
        assertEquals("5:00", ChamberCatalog.formatSeconds(300.0))
        assertEquals("2:30", ChamberCatalog.formatSeconds(150.0))
    }
}
