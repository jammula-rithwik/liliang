package com.liliang.app

import com.liliang.app.data.*
import com.liliang.app.engine.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgressionEngineTest {

    private fun session(date: String, vararg sets: Pair<Double, Int>) = LiftSession(
        date = date,
        sets = sets.map { SetPerformance(weight = it.first, reps = it.second) },
    )

    /** Tests pin `today` to just after the latest session so layoff logic stays out of the way. */
    private val dayAfter = { date: String -> LocalDate.parse(date).plusDays(2) }

    // ─── e1RM ───

    @Test
    fun epleyMatchesKnownValues() {
        assertEquals(100.0, epleyE1rm(100.0, 1)!!, 3.4) // 100×1 ≈ 103.3
        assertEquals(116.6, epleyE1rm(100.0, 5)!!, 0.1)
        assertNull(epleyE1rm(null, 5))
        assertNull(epleyE1rm(100.0, 0))
    }

    @Test
    fun epleyCapsRepsAtFifteen() {
        assertEquals(epleyE1rm(50.0, 15), epleyE1rm(50.0, 30))
    }

    // ─── stall detection ───

    @Test
    fun freshPrCountsAsZeroStalled() {
        val sessions = listOf(
            session("2026-01-15", 82.5 to 5), // e1RM 96.25 — PR
            session("2026-01-08", 80.0 to 5), // 93.3
            session("2026-01-01", 77.5 to 5),
        )
        assertEquals(0, ProgressionEngine.stalledSessionCount(sessions))
    }

    @Test
    fun countsConsecutiveSessionsWithoutPr() {
        val sessions = listOf(
            session("2026-01-22", 80.0 to 5), // no PR
            session("2026-01-15", 80.0 to 5), // no PR
            session("2026-01-08", 80.0 to 5), // no PR
            session("2026-01-01", 80.0 to 6), // the PR everything is judged against
        )
        assertEquals(3, ProgressionEngine.stalledSessionCount(sessions))
    }

    // ─── starting & layoffs ───

    @Test
    fun noHistoryStartsFromTemplate() {
        val prescription = ProgressionEngine.prescribeLift(
            history = LiftHistory("Viking Press"),
            templateSets = 4,
            templateReps = 8,
            templateWeight = 40.0,
            today = LocalDate.of(2026, 1, 20),
        )
        assertEquals(PrescriptionIntent.START, prescription.intent)
        assertEquals(4, prescription.sets)
        assertEquals(8, prescription.reps)
        assertEquals(40.0, prescription.weightKg!!, 0.01)
    }

    @Test
    fun layoffTriggersRebuildNotDeload() {
        // Stalled history AND a 9-week gap: the gap must win — prescribing a
        // "light week" to someone who just had two months off is nonsense.
        val sessions = (0 until 7).map { weeksAgo ->
            session(LocalDate.of(2026, 1, 26).minusWeeks(weeksAgo.toLong()).toString(), 100.0 to 5, 100.0 to 5)
        }
        val prescription = ProgressionEngine.prescribeLift(
            history = LiftHistory("Squat", sessions),
            templateSets = 5,
            templateReps = 5,
            today = LocalDate.of(2026, 3, 30), // ~9 weeks after the last session
        )
        assertEquals(PrescriptionIntent.REBUILD, prescription.intent)
        assertEquals(85.0, prescription.weightKg!!, 0.01) // 100 × 0.85 (63 days)
        assertTrue(prescription.rationale.contains("weeks"))
    }

    @Test
    fun shortLayoffRestartsAtNinetyPercent() {
        val history = LiftHistory(
            "Incline Bench Press",
            listOf(session("2026-01-01", 75.0 to 8, 75.0 to 8, 75.0 to 8, 75.0 to 8)),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            templateSets = 4,
            templateReps = 8,
            today = LocalDate.of(2026, 1, 26), // 25 days later
        )
        assertEquals(PrescriptionIntent.REBUILD, prescription.intent)
        assertEquals(67.5, prescription.weightKg!!, 0.01) // 75 × 0.9 rounded to 2.5
    }

    // ─── treatments ───

    @Test
    fun strengthTreatmentProgressesLinearly() {
        val settings = WorkoutPlanSettings(emphasisStrength = 60, emphasisHypertrophy = 20, emphasisPower = 20)
        val history = LiftHistory(
            "Bench Press",
            listOf(
                session("2026-01-15", 100.0 to 5, 100.0 to 5, 100.0 to 5, 100.0 to 5, 100.0 to 5),
                session("2026-01-08", 97.5 to 5, 97.5 to 5, 97.5 to 5, 97.5 to 5, 97.5 to 5),
            ),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            role = ProgressionRole.PRIMARY,
            settings = settings,
            stepKg = 2.5,
            today = dayAfter("2026-01-15"),
        )
        assertEquals(PrescriptionIntent.PROGRESS, prescription.intent)
        assertEquals(5, prescription.reps)
        assertEquals(102.5, prescription.weightKg!!, 0.01)
    }

    @Test
    fun hypertrophyUsesDoubleProgressionAddingReps() {
        // 4×8 at 70 completed → same weight, aim 9s (not +2.5 kg).
        val history = LiftHistory(
            "Incline Row",
            listOf(
                session("2026-01-15", 70.0 to 8, 70.0 to 8, 70.0 to 8, 70.0 to 8),
                session("2026-01-08", 70.0 to 7, 70.0 to 8, 70.0 to 7, 70.0 to 7),
            ),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            role = ProgressionRole.ACCESSORY,
            templateSets = 4,
            templateReps = 8,
            today = dayAfter("2026-01-15"),
        )
        assertEquals(PrescriptionIntent.PROGRESS, prescription.intent)
        assertEquals(9, prescription.reps)
        assertEquals(70.0, prescription.weightKg!!, 0.01)
    }

    @Test
    fun hypertrophyToppedOutAddsWeightAndResetsReps() {
        val history = LiftHistory(
            "Incline Row",
            listOf(
                session("2026-01-15", 70.0 to 12, 70.0 to 12, 70.0 to 12, 70.0 to 12),
                session("2026-01-08", 70.0 to 11, 70.0 to 11, 70.0 to 11, 70.0 to 11),
            ),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            role = ProgressionRole.ACCESSORY,
            templateSets = 4,
            templateReps = 8,
            today = dayAfter("2026-01-15"),
        )
        assertEquals(PrescriptionIntent.PROGRESS, prescription.intent)
        assertEquals(8, prescription.reps)
        assertEquals(72.5, prescription.weightKg!!, 0.01)
    }

    @Test
    fun powerCategoryUsesPercentProtocol() {
        // e1RM ≈ 116.6 (100×5); 50% → 57.5 rounded. 3 sets, reps ladder from the range.
        val history = LiftHistory(
            "Box Jump Press",
            listOf(session("2026-01-15", 50.0 to 6, 50.0 to 6, 50.0 to 6), session("2026-01-08", 100.0 to 5)),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            exerciseCategory = "Power",
            today = dayAfter("2026-01-15"),
        )
        assertEquals(3, prescription.sets)
        assertEquals(7, prescription.reps) // last min 6 → +1, within 5–8
        assertEquals(57.5, prescription.weightKg!!, 0.01) // 116.6 × 0.5 → 58.3 → 57.5 step
        assertTrue(prescription.rationale.contains("explosive"))
    }

    // ─── plateaus & waves ───

    @Test
    fun hypertrophyPlateauSwitchesToHeavyWave() {
        // Four sessions of 8s with no e1RM movement → intensity wave at low reps.
        val history = LiftHistory(
            "Incline Bench Press",
            listOf(
                session("2026-01-29", 70.0 to 8, 70.0 to 7, 70.0 to 7),
                session("2026-01-22", 70.0 to 8, 70.0 to 7, 70.0 to 7),
                session("2026-01-15", 70.0 to 8, 70.0 to 7, 70.0 to 7),
                session("2026-01-08", 70.0 to 8, 70.0 to 8, 70.0 to 7),
            ),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            role = ProgressionRole.PRIMARY,
            templateSets = 3,
            templateReps = 8,
            today = dayAfter("2026-01-29"),
        )
        assertEquals(PrescriptionIntent.INTENSITY_WAVE, prescription.intent)
        assertEquals(3, prescription.reps)
        // Heavy triples should be meaningfully heavier than the stalled 70 kg working weight.
        assertTrue(prescription.weightKg!! > 75.0)
        assertTrue(prescription.rationale.isNotBlank())
    }

    @Test
    fun heavyPlateauSwitchesToVolumeWave() {
        val history = LiftHistory(
            "Deadlift",
            listOf(
                session("2026-01-29", 140.0 to 3, 140.0 to 3),
                session("2026-01-22", 140.0 to 3, 140.0 to 3),
                session("2026-01-15", 140.0 to 3, 140.0 to 3),
                session("2026-01-08", 140.0 to 3, 140.0 to 3),
            ),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            role = ProgressionRole.PRIMARY,
            templateSets = 4,
            templateReps = 3,
            today = dayAfter("2026-01-29"),
        )
        assertEquals(PrescriptionIntent.VOLUME_WAVE, prescription.intent)
        assertEquals(8, prescription.reps)
        assertTrue(prescription.weightKg!! < 140.0)
    }

    @Test
    fun isolationPlateauNeverGoesBelowSixReps() {
        // A stalled fly should never be told to do heavy triples.
        val history = LiftHistory(
            "Fly",
            listOf(
                session("2026-01-29", 80.0 to 8, 80.0 to 8),
                session("2026-01-22", 80.0 to 8, 80.0 to 8),
                session("2026-01-15", 80.0 to 8, 80.0 to 8),
                session("2026-01-08", 80.0 to 8, 80.0 to 8),
            ),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            isCompound = false,
            templateSets = 4,
            templateReps = 8,
            today = dayAfter("2026-01-29"),
        )
        assertTrue("isolation wave must stay ≥6 reps", prescription.reps >= 6)
        assertNotEquals(PrescriptionIntent.TEST, prescription.intent)
    }

    @Test
    fun isolationNeverGetsTestSingles() {
        // Goal within reach, but isolation movements don't max out.
        val history = LiftHistory(
            "Fly",
            listOf(session("2026-01-15", 98.0 to 5, 98.0 to 4)),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            isCompound = false,
            goalWeightKg = 100.0,
            templateSets = 4,
            templateReps = 8,
            today = dayAfter("2026-01-15"),
        )
        assertNotEquals(PrescriptionIntent.TEST, prescription.intent)
        assertTrue(prescription.reps >= 6)
    }

    @Test
    fun longStallForcesDeload() {
        val sessions = (0 until 7).map { index ->
            session(LocalDate.of(2026, 3, 1).minusDays(index * 4L).toString(), 100.0 to 5, 100.0 to 5, 100.0 to 5, 100.0 to 5, 100.0 to 5)
        }
        val prescription = ProgressionEngine.prescribeLift(
            history = LiftHistory("Squat", sessions),
            templateSets = 5,
            templateReps = 5,
            today = dayAfter("2026-03-01"),
        )
        assertEquals(PrescriptionIntent.DELOAD, prescription.intent)
        assertEquals(85.0, prescription.weightKg!!, 0.01)
    }

    @Test
    fun goalWithinReachPrescribesTestDay() {
        val history = LiftHistory(
            "Incline Bench Press",
            listOf(session("2026-01-15", 87.5 to 5, 87.5 to 4)), // e1RM ≈ 102
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            goalWeightKg = 100.0,
            templateSets = 4,
            templateReps = 5,
            today = dayAfter("2026-01-15"),
        )
        assertEquals(PrescriptionIntent.TEST, prescription.intent)
        assertEquals(100.0, prescription.weightKg!!, 0.01)
        assertEquals(1, prescription.reps)
    }

    @Test
    fun powerEmphasisPlateauPrescribesSpeedWork() {
        val history = LiftHistory(
            "Bench Press",
            listOf(
                session("2026-01-29", 100.0 to 5),
                session("2026-01-22", 100.0 to 5),
                session("2026-01-15", 100.0 to 5),
                session("2026-01-08", 100.0 to 5),
            ),
        )
        val prescription = ProgressionEngine.prescribeLift(
            history = history,
            settings = WorkoutPlanSettings(emphasisStrength = 20, emphasisHypertrophy = 20, emphasisPower = 60),
            templateSets = 5,
            templateReps = 5,
            today = dayAfter("2026-01-29"),
        )
        assertEquals(PrescriptionIntent.SPEED_WAVE, prescription.intent)
        assertEquals(6, prescription.sets)
        assertEquals(3, prescription.reps)
    }

    @Test
    fun workingWeightIgnoresWarmups() {
        val s = session("2026-01-15", 40.0 to 10, 60.0 to 5, 75.0 to 5, 75.0 to 5, 75.0 to 4)
        assertEquals(75.0, ProgressionEngine.workingWeight(s)!!, 0.01)
    }

    @Test
    fun workingSetMinRepsExcludesWarmups() {
        val s = session("2026-01-15", 40.0 to 12, 75.0 to 8, 75.0 to 7, 75.0 to 6)
        assertEquals(6, ProgressionEngine.workingSetMinReps(s))
    }

    // ─── treatment selection ───

    @Test
    fun isolationAlwaysGetsHypertrophyTreatment() {
        val strengthPlan = WorkoutPlanSettings(emphasisStrength = 80, emphasisHypertrophy = 10, emphasisPower = 10)
        assertEquals(
            LiftTreatment.HYPERTROPHY,
            ProgressionEngine.treatmentFor("Hypertrophy", isCompound = false, ProgressionRole.PRIMARY, strengthPlan, templateReps = 5),
        )
    }

    @Test
    fun powerCategoryAlwaysGetsPowerTreatment() {
        assertEquals(
            LiftTreatment.POWER,
            ProgressionEngine.treatmentFor("Power", isCompound = true, ProgressionRole.ACCESSORY, WorkoutPlanSettings(), templateReps = null),
        )
    }

    @Test
    fun lowRepTemplateOnCompoundReadsAsStrength() {
        assertEquals(
            LiftTreatment.STRENGTH,
            ProgressionEngine.treatmentFor("Hypertrophy", isCompound = true, ProgressionRole.PRIMARY, WorkoutPlanSettings(), templateReps = 5),
        )
    }

    @Test
    fun compoundNameInferenceWorks() {
        assertTrue(inferIsCompound("Incline Bench Press"))
        assertTrue(inferIsCompound("Viking Press"))
        assertTrue(inferIsCompound("Barbell Row"))
        assertTrue(!inferIsCompound("Fly"))
        assertTrue(!inferIsCompound("Roman Chair"))
    }

    // ─── runs ───

    @Test
    fun firstRunEverIsEasyBaseline() {
        val prescription = ProgressionEngine.prescribeRun(recentRuns = emptyList())
        assertEquals(RunKind.EASY, prescription.kind)
    }

    @Test
    fun timeGoalMakesSpeedWorkTheWeeksPriority() {
        val runs = listOf(
            RunRecord("2026-01-20", 5.0, 2000, 400.0), // easy
            RunRecord("2026-01-17", 10.0, 3900, 390.0), // long
        )
        val goal = TrainingGoal(
            kind = GoalKind.RUN_TIME,
            title = "5k in 25",
            targetDistanceKm = 5.0,
            targetDurationSeconds = 1500,
        )
        val prescription = ProgressionEngine.prescribeRun(
            recentRuns = runs,
            goalTime = goal,
            today = LocalDate.of(2026, 1, 22),
        )
        // No quality work in the last 7 days → intervals are due.
        assertEquals(RunKind.INTERVALS, prescription.kind)
        assertNotNull(prescription.paceSecondsPerKm)
        // Interval pace should be faster than goal pace (300 s/km).
        assertTrue(prescription.paceSecondsPerKm!! < 300)
    }

    @Test
    fun weeklyVolumeGuardKeepsExtraRunsShort() {
        val runs = listOf(
            RunRecord("2026-01-21", 10.0, 3900, 390.0),
            RunRecord("2026-01-19", 8.0, 3100, 388.0),
            RunRecord("2026-01-17", 7.0, 2700, 386.0),
        )
        val prescription = ProgressionEngine.prescribeRun(
            recentRuns = runs,
            weeklyKmTarget = 20.0,
            today = LocalDate.of(2026, 1, 22),
        )
        assertEquals(RunKind.EASY, prescription.kind)
        assertTrue(prescription.distanceKm!! <= 5.0)
    }

    @Test
    fun longRunGrowsTowardDistanceGoal() {
        // Last long run was over a week ago → the weekly long run is due,
        // and it grows 10% toward the goal distance.
        val runs = listOf(
            RunRecord("2026-01-20", 4.0, 1680, 420.0), // easy
            RunRecord("2026-01-13", 10.0, 3950, 395.0), // long, 9 days ago
        )
        val prescription = ProgressionEngine.prescribeRun(
            recentRuns = runs,
            goalDistanceKm = 21.1,
            today = LocalDate.of(2026, 1, 22),
        )
        assertEquals(RunKind.LONG, prescription.kind)
        assertEquals(11.0, prescription.distanceKm!!, 0.01) // 10 km + 10%
    }

    @Test
    fun recentLongRunMeansEasyMiles() {
        // Long run done 2 days ago, no time goal → today is easy miles.
        val runs = listOf(
            RunRecord("2026-01-20", 10.0, 3950, 395.0),
            RunRecord("2026-01-17", 4.0, 1680, 420.0),
        )
        val prescription = ProgressionEngine.prescribeRun(
            recentRuns = runs,
            goalDistanceKm = 21.1,
            today = LocalDate.of(2026, 1, 22),
        )
        assertEquals(RunKind.EASY, prescription.kind)
    }

    // ─── trend / ETA ───

    @Test
    fun trendSlopeFitsALine() {
        val points = listOf(
            LocalDate.of(2026, 1, 1) to 90.0,
            LocalDate.of(2026, 1, 8) to 91.0,
            LocalDate.of(2026, 1, 15) to 92.0,
            LocalDate.of(2026, 1, 22) to 93.0,
        )
        val slope = trendSlopePerDay(points)!!
        assertEquals(1.0 / 7.0, slope, 0.001)
    }

    @Test
    fun etaProjectsForward() {
        val today = LocalDate.of(2026, 1, 22)
        val eta = projectEta(current = 93.0, target = 100.0, slopePerDay = 1.0 / 7.0, today = today)
        assertEquals(today.plusDays(49), eta)
    }

    @Test
    fun etaNullWithoutPositiveTrend() {
        assertNull(projectEta(90.0, 100.0, slopePerDay = 0.0))
        assertNull(projectEta(90.0, 100.0, slopePerDay = null))
    }

    @Test
    fun roundToStepSnapsToPlates() {
        assertEquals(72.5, 72.4.roundToStep(2.5), 0.001)
        assertEquals(72.5, 73.7.roundToStep(2.5), 0.001)
        assertEquals(75.0, 73.8.roundToStep(2.5), 0.001)
    }

    // ─── bodyweight progression ───

    private fun bwSession(date: String, vararg reps: Int) = LiftSession(
        date = date,
        sets = reps.map { SetPerformance(weight = null, reps = it) },
    )

    @Test
    fun bodyweightWithNoHistoryStarts() {
        val p = ProgressionEngine.prescribeLift(
            history = LiftHistory("Pull-ups", emptyList()),
            isBodyweight = true,
        )
        assertEquals(PrescriptionIntent.START, p.intent)
        assertNull(p.weightKg)
    }

    @Test
    fun bodyweightProgressesOnRepsWhenEverySetHitBest() {
        val p = ProgressionEngine.prescribeLift(
            history = LiftHistory("Pull-ups", listOf(bwSession("2026-06-01", 8, 8, 8))),
            isBodyweight = true,
        )
        assertEquals(PrescriptionIntent.PROGRESS, p.intent)
        assertEquals(9, p.reps)
        assertNull(p.weightKg)
    }

    @Test
    fun bodyweightHoldsUntilAllSetsMatchBest() {
        val p = ProgressionEngine.prescribeLift(
            history = LiftHistory("Pull-ups", listOf(bwSession("2026-06-01", 8, 6, 5))),
            isBodyweight = true,
        )
        assertEquals(PrescriptionIntent.HOLD, p.intent)
        assertEquals(8, p.reps)
        assertNull(p.weightKg)
    }

    // ── F1 (4): duration-only history must not derail a lift prescription ──
    // Post-R10, a lift's rows can be durationSeconds-only (weight/reps null).
    // The engine must treat that as "no usable history" and fall back to the
    // template — never NaN, never a phantom weight.
    @Test
    fun `duration-only history falls back to template targets`() {
        val history = LiftHistory(
            exerciseName = "RKC Plank",
            sessions = listOf(
                LiftSession(date = "2026-08-20", sets = List(3) { SetPerformance(weight = null, reps = null) }),
                LiftSession(date = "2026-08-13", sets = List(3) { SetPerformance(weight = null, reps = null) }),
            ),
        )
        val p = ProgressionEngine.prescribeLift(
            history = history,
            templateSets = 3,
            templateReps = 10,
            templateWeight = null,
        )
        assertEquals(3, p.sets)
        assertEquals(10, p.reps)
        assertNull(p.weightKg)
        assertFalse(p.rationale.contains("NaN"))
    }

    // ── F1 (5): the long-run floor scales with the runner (#48's fix, pinned) ──
    @Test
    fun `long run floor scales down for short-distance runners`() {
        // Recent longest 4 km: a 3.6 km run IS a long run for this runner —
        // the old hard 6 km floor said EASY forever.
        val shortRunner = ProgressionEngine.classifyRun(
            RunRecord(date = "2026-08-20", distanceKm = 3.6),
            medianPace = null,
            longestRecent = 4.0,
        )
        assertEquals(RunKind.LONG, shortRunner)
        // But the floor never drops below 3 km — a 2.6 km jog is not "long".
        val tiny = ProgressionEngine.classifyRun(
            RunRecord(date = "2026-08-20", distanceKm = 2.6),
            medianPace = null,
            longestRecent = 3.0,
        )
        assertEquals(RunKind.EASY, tiny)
    }

    @Test
    fun `pace thresholds split intervals, tempo, easy`() {
        val median = 360.0
        assertEquals(
            RunKind.INTERVALS,
            ProgressionEngine.classifyRun(RunRecord(date = "d", distanceKm = 5.0, paceSecondsPerKm = 300.0), median, longestRecent = 10.0),
        )
        assertEquals(
            RunKind.TEMPO,
            ProgressionEngine.classifyRun(RunRecord(date = "d", distanceKm = 5.0, paceSecondsPerKm = 330.0), median, longestRecent = 10.0),
        )
        assertEquals(
            RunKind.EASY,
            ProgressionEngine.classifyRun(RunRecord(date = "d", distanceKm = 5.0, paceSecondsPerKm = 355.0), median, longestRecent = 10.0),
        )
    }
}
