package com.liliang.app

import com.liliang.app.data.PlanDayType
import com.liliang.app.data.WorkoutPlan
import com.liliang.app.data.WorkoutPlanDay
import com.liliang.app.data.planSlotFor
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the ONE date→plan-slot mapping (Monday anchor + repeatWeekly wrap).
 * A second, start-anchored copy of this arithmetic once edited the wrong
 * weekday for non-Monday plans and refused rolling plans past week one —
 * these tests exist so the two behaviours can never fork again.
 */
class PlanSlotTest {

    private fun day(week: Int, dayIndex: Int, label: String) = WorkoutPlanDay(
        weekIndex = week,
        dayIndex = dayIndex,
        dayLabel = label,
        dayType = PlanDayType.CUSTOM,
        customWorkoutName = label,
    )

    // A one-week rolling plan started mid-week (Wednesday 2026-08-26).
    private val rollingPlan = WorkoutPlan(
        name = "S1",
        startDate = "2026-08-26",
        weeks = 1,
        repeatWeekly = true,
        days = listOf(day(0, 0, "Mon"), day(0, 4, "Fri")),
    )

    @Test
    fun `dayIndex is anchored to real weekdays, not the start weekday`() {
        // Friday of the start week must resolve to the Fri slot (dayIndex 4) —
        // the start-anchored math called this offset 2, i.e. the Mon slot + 2.
        val friday = LocalDate.parse("2026-08-28")
        assertEquals("Fri", planSlotFor(rollingPlan, friday)?.dayLabel)
    }

    @Test
    fun `repeatWeekly wraps months past the start`() {
        // A Monday ~10 weeks in: the rolling plan must still serve its Mon slot.
        val laterMonday = LocalDate.parse("2026-11-02")
        assertEquals("Mon", planSlotFor(rollingPlan, laterMonday)?.dayLabel)
    }

    @Test
    fun `days in the start week before the start date are not served`() {
        // The plan started Wednesday; that week's Monday precedes it.
        assertNull(planSlotFor(rollingPlan, LocalDate.parse("2026-08-24")))
    }

    @Test
    fun `non-repeating plans end after their weeks`() {
        val fixed = rollingPlan.copy(repeatWeekly = false)
        // Start week still serves…
        assertEquals("Fri", planSlotFor(fixed, LocalDate.parse("2026-08-28"))?.dayLabel)
        // …the next Monday is week 1 of a 1-week plan: nothing.
        assertNull(planSlotFor(fixed, LocalDate.parse("2026-08-31")))
    }

    @Test
    fun `two-week plans serve their second week by Monday counting`() {
        val twoWeek = WorkoutPlan(
            name = "block",
            startDate = "2026-08-26", // Wednesday
            weeks = 2,
            repeatWeekly = false,
            days = listOf(day(0, 4, "W1 Fri"), day(1, 4, "W2 Fri")),
        )
        assertEquals("W1 Fri", planSlotFor(twoWeek, LocalDate.parse("2026-08-28"))?.dayLabel)
        assertEquals("W2 Fri", planSlotFor(twoWeek, LocalDate.parse("2026-09-04"))?.dayLabel)
        assertNull(planSlotFor(twoWeek, LocalDate.parse("2026-09-11")))
    }
}
