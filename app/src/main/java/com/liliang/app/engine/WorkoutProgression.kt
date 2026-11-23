package com.liliang.app.engine

import com.liliang.app.data.*

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// The progression engine: pure functions that turn training history + goals +
// plan emphasis into concrete prescriptions (sets × reps × weight, or a run
// with distance/pace), each with a human-readable rationale.
//
// Design rules:
//   - No database or Android dependencies — everything arrives as plain data
//     so the engine is unit-testable and the repository stays the only place
//     that knows SQL.
//   - Never just "add 2.5 kg forever": the engine watches estimated-1RM trend
//     per exercise, detects plateaus, and rotates the stimulus (heavy low-rep
//     waves, volume waves, deloads, test days) the way a coach would.
//   - Every output carries a [rationale] explaining itself in one sentence.
//     Trust in the recommendation comes from being able to read why.

// ───────────────────────── Inputs ─────────────────────────

/** One historical set of a lift: weight × reps (rpe optional). */
data class SetPerformance(
    val weight: Double? = null,
    val reps: Int? = null,
    val rpe: Int? = null,
)

/** Every set of one exercise on one date. */
data class LiftSession(
    val date: String,
    val sets: List<SetPerformance> = emptyList(),
) {
    /** Estimated 1RM for the best set of the session (Epley), null if nothing parses. */
    val bestE1rm: Double?
        get() = sets.mapNotNull { epleyE1rm(it.weight, it.reps) }.maxOrNull()

    val topWeight: Double?
        get() = sets.mapNotNull { it.weight }.maxOrNull()

    val totalVolume: Double
        get() = sets.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) }

    val averageReps: Double?
        get() = sets.mapNotNull { it.reps }.takeIf { it.isNotEmpty() }?.average()
}

/** Recent history of one exercise, newest session first. */
data class LiftHistory(
    val exerciseName: String,
    val sessions: List<LiftSession> = emptyList(),
)

/**
 * Estimated 1RM via Epley: w × (1 + reps/30). Returns null when weight/reps are
 * missing or silly. Reps are capped at 15 — rep-maxes beyond that say more
 * about conditioning than strength and would inflate the estimate.
 */
fun epleyE1rm(weight: Double?, reps: Int?): Double? {
    if (weight == null || weight <= 0.0) return null
    if (reps == null || reps <= 0) return null
    val cappedReps = reps.coerceAtMost(15)
    return weight * (1.0 + cappedReps / 30.0)
}

/** Inverse Epley: the weight that makes [reps] equal to [e1rm] effort. */
fun weightForReps(e1rm: Double, reps: Int): Double = e1rm / (1.0 + reps.coerceAtMost(15) / 30.0)

// ───────────────────────── Outputs ─────────────────────────

enum class PrescriptionIntent(val label: String) {
    START("First exposure"),
    REBUILD("Back from a break"),
    PROGRESS("Progress"),
    HOLD("Consolidate"),
    INTENSITY_WAVE("Heavy wave"),
    VOLUME_WAVE("Volume wave"),
    SPEED_WAVE("Speed wave"),
    DELOAD("Deload"),
    TEST("Test day"),
}

data class LiftPrescription(
    val sets: Int,
    val reps: Int,
    val weightKg: Double? = null,
    val intent: PrescriptionIntent = PrescriptionIntent.PROGRESS,
    val rationale: String = "",
)

// One vocabulary for run kinds everywhere — coach copy, quick-log chips and
// stored workout titles all read from these labels.
enum class RunKind(val label: String) {
    EASY("Easy Run"),
    INTERVALS("Intervals"),
    TEMPO("Tempo Run"),
    LONG("Long Run"),
}

data class RunPrescription(
    val kind: RunKind,
    val distanceKm: Double? = null,
    val durationSeconds: Int? = null,
    val paceSecondsPerKm: Int? = null,
    val rationale: String = "",
)

// ───────────────────────── Engine ─────────────────────────

/**
 * How strongly each training quality is emphasised, from plan settings.
 * The three weights need not sum to anything in particular — they're
 * compared relatively.
 */
data class TrainingEmphasis(
    val strength: Int = 40,
    val hypertrophy: Int = 40,
    val power: Int = 20,
) {
    val dominant: EmphasisKind
        get() = when {
            power >= strength && power >= hypertrophy -> EmphasisKind.POWER
            strength >= hypertrophy -> EmphasisKind.STRENGTH
            else -> EmphasisKind.HYPERTROPHY
        }
}

enum class EmphasisKind { STRENGTH, HYPERTROPHY, POWER }

/** Which protocol the engine runs an exercise under. */
internal enum class LiftTreatment { POWER, STRENGTH, HYPERTROPHY }

object ProgressionEngine {

    /** Sessions without a new e1RM before we change the stimulus. */
    private const val PLATEAU_AFTER = 3

    /** Within this fraction of a lift goal, prescribe a test day. */
    private const val GOAL_TEST_PROXIMITY = 0.97

    /**
     * Prescribe the next session for a lift.
     *
     * [history] is newest-first. [exerciseCategory] is the library category
     * ("Power" exercises run the percent-based power protocol); [isCompound]
     * gates heavy low-rep work — isolation movements never go below 6 reps
     * and never get test singles. [templateSets]/[templateReps]/
     * [templateWeight] seed the scheme; [goalWeightKg] is a LIFT_TARGET goal.
     * [today] exists for tests and for layoff detection — an exercise not
     * trained in [WorkoutPlanSettings.layoffDays] gets a rebuild ramp, not a
     * stall diagnosis.
     */
    fun prescribeLift(
        history: LiftHistory,
        role: ProgressionRole = ProgressionRole.ACCESSORY,
        settings: WorkoutPlanSettings = WorkoutPlanSettings(),
        stepKg: Double = 2.5,
        exerciseCategory: String = "",
        isCompound: Boolean = true,
        isBodyweight: Boolean = false,
        templateSets: Int? = null,
        templateReps: Int? = null,
        templateWeight: Double? = null,
        goalWeightKg: Double? = null,
        today: LocalDate = LocalDate.now(),
    ): LiftPrescription {
        val sessions = history.sessions.filter { it.bestE1rm != null }
        val treatment = treatmentFor(exerciseCategory, isCompound, role, settings, templateReps)
        val scheme = schemeFor(treatment, settings, templateSets, templateReps)

        // Bodyweight movements have no bar weight to trend — they progress on
        // reps: match your best on every set, then the target climbs. (Without
        // this branch a weight-filtered history left pull-ups on "START"
        // forever — audit #22.)
        if (isBodyweight) {
            return prescribeBodyweight(history, scheme, settings, templateReps)
        }

        // No usable history: start from the template, or conservatively.
        if (sessions.isEmpty()) {
            return LiftPrescription(
                sets = scheme.first,
                reps = scheme.second,
                weightKg = templateWeight?.roundToStep(stepKg),
                intent = PrescriptionIntent.START,
                rationale = "No history for ${history.exerciseName} yet — starting at the planned weight. Leave a rep or two in the tank.",
            )
        }

        val last = sessions.first()
        val bestE1rm = sessions.mapNotNull { it.bestE1rm }.max()
        val stalled = stalledSessionCount(sessions)
        val lastWorkingWeight = workingWeight(last) ?: templateWeight ?: 0.0

        // Layoff beats everything: a gap this long makes stall counting and
        // PR chasing meaningless. Restart a notch down and rebuild — strength
        // returns much faster than it was gained.
        val daysSinceLast = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(last.date), today) }.getOrDefault(0L)
        if (daysSinceLast >= settings.layoffDays && lastWorkingWeight > 0) {
            val factor = when {
                daysSinceLast <= 42 -> 0.90
                daysSinceLast <= 70 -> 0.85
                else -> 0.80
            }
            val weeks = (daysSinceLast / 7).toInt()
            val rebuildWeight = (lastWorkingWeight * factor).roundToStep(stepKg)
            return LiftPrescription(
                sets = scheme.first,
                reps = scheme.second,
                weightKg = rebuildWeight,
                intent = PrescriptionIntent.REBUILD,
                rationale = "First ${history.exerciseName} session in $weeks weeks — restarting at ${rebuildWeight.formatKg()} (~${(factor * 100).roundToInt()}%) to rebuild. It comes back fast.",
            )
        }

        // Power-category movements: fixed percent of e1RM, moved fast, reps
        // laddering within the power range. The weight grows as the e1RM
        // grows from strength work — power work itself shouldn't grind.
        if (treatment == LiftTreatment.POWER) {
            val powerWeight = (bestE1rm * settings.powerPercent / 100.0).roundToStep(stepKg).coerceAtLeast(stepKg)
            val lastMin = workingSetMinReps(last)
            val targetReps = when {
                lastMin == null -> settings.powerRepMin
                lastMin >= settings.powerRepMax -> settings.powerRepMin
                else -> (lastMin + 1).coerceIn(settings.powerRepMin, settings.powerRepMax)
            }
            return LiftPrescription(
                sets = settings.powerSets,
                reps = targetReps,
                weightKg = powerWeight,
                intent = PrescriptionIntent.PROGRESS,
                rationale = "Power: ${settings.powerSets}×$targetReps at ~${settings.powerPercent}% (${powerWeight.formatKg()}) — every rep as explosive as possible. Bar speed is the goal, not fatigue.",
            )
        }

        // Goal within reach → test day. Compounds only — maxing an isolation
        // movement is how elbows file complaints.
        if (isCompound && goalWeightKg != null && goalWeightKg > 0 && bestE1rm >= goalWeightKg * GOAL_TEST_PROXIMITY) {
            return LiftPrescription(
                sets = 5,
                reps = 1,
                weightKg = goalWeightKg.roundToStep(stepKg),
                intent = PrescriptionIntent.TEST,
                rationale = "Estimated 1RM (${bestE1rm.formatKg()}) is within 3% of your ${goalWeightKg.formatKg()} goal — warm up, then attempt it.",
            )
        }

        // Long stall → deload, reset, then re-ramp.
        if (stalled >= settings.deloadAfterStalls) {
            val deloadWeight = (lastWorkingWeight * 0.85).roundToStep(stepKg)
            return LiftPrescription(
                sets = max(2, scheme.first - 1),
                reps = scheme.second,
                weightKg = deloadWeight,
                intent = PrescriptionIntent.DELOAD,
                rationale = "No estimated-1RM progress in $stalled sessions — take a light week (−15%) and rebuild momentum.",
            )
        }

        // Medium stall → change the stimulus instead of grinding.
        if (stalled >= PLATEAU_AFTER) {
            return plateauWave(last, bestE1rm, stalled, settings, stepKg, isCompound, lastWorkingWeight)
        }

        // Progressing — by treatment.
        return when (treatment) {
            LiftTreatment.STRENGTH -> strengthProgression(last, scheme, lastWorkingWeight, stepKg)
            else -> doubleProgression(last, scheme.first, settings, lastWorkingWeight, stepKg)
        }
    }

    /** Classic linear strength progression: hit every rep → add weight. */
    private fun strengthProgression(
        last: LiftSession,
        scheme: Pair<Int, Int>,
        lastWorkingWeight: Double,
        stepKg: Double,
    ): LiftPrescription {
        val targetReps = scheme.second
        val completedAllSets = last.sets.count { (it.reps ?: 0) >= targetReps } >= max(1, scheme.first)
        return if (completedAllSets) {
            val newWeight = (lastWorkingWeight + stepKg).roundToStep(stepKg)
            LiftPrescription(
                sets = scheme.first,
                reps = targetReps,
                weightKg = newWeight,
                intent = PrescriptionIntent.PROGRESS,
                rationale = "Hit ${scheme.first}×$targetReps at ${lastWorkingWeight.formatKg()} last time — moving up to ${newWeight.formatKg()}.",
            )
        } else {
            LiftPrescription(
                sets = scheme.first,
                reps = targetReps,
                weightKg = lastWorkingWeight.roundToStep(stepKg),
                intent = PrescriptionIntent.HOLD,
                rationale = "Reps fell short of ${scheme.first}×$targetReps last session — repeat ${lastWorkingWeight.formatKg()} and own every rep before adding weight.",
            )
        }
    }

    /**
     * Hypertrophy double progression in [WorkoutPlanSettings.hypRepMin]–
     * [WorkoutPlanSettings.hypRepMax]: climb the rep range at a fixed weight;
     * top out every set → add weight and drop back to the bottom.
     */
    private fun doubleProgression(
        last: LiftSession,
        sets: Int,
        settings: WorkoutPlanSettings,
        lastWorkingWeight: Double,
        stepKg: Double,
    ): LiftPrescription {
        val lastMin = workingSetMinReps(last) ?: settings.hypRepMin
        return when {
            lastMin >= settings.hypRepMax -> {
                val newWeight = (lastWorkingWeight + stepKg).roundToStep(stepKg)
                LiftPrescription(
                    sets = sets,
                    reps = settings.hypRepMin,
                    weightKg = newWeight,
                    intent = PrescriptionIntent.PROGRESS,
                    rationale = "Every set reached ${settings.hypRepMax} — up to ${newWeight.formatKg()} and back to ${settings.hypRepMin}s. The climb starts again.",
                )
            }
            lastMin < settings.hypRepMin - 1 -> LiftPrescription(
                sets = sets,
                reps = settings.hypRepMin,
                weightKg = lastWorkingWeight.roundToStep(stepKg),
                intent = PrescriptionIntent.HOLD,
                rationale = "Reps dropped to $lastMin — hold ${lastWorkingWeight.formatKg()} and secure ${settings.hypRepMin}s across all sets first.",
            )
            else -> {
                val targetReps = (lastMin + 1).coerceIn(settings.hypRepMin, settings.hypRepMax)
                LiftPrescription(
                    sets = sets,
                    reps = targetReps,
                    weightKg = lastWorkingWeight.roundToStep(stepKg),
                    intent = PrescriptionIntent.PROGRESS,
                    rationale = "Same ${lastWorkingWeight.formatKg()}, one more rep — aim $sets×$targetReps, add weight when ${settings.hypRepMax}s are yours.",
                )
            }
        }
    }

    /**
     * Pick the wave that breaks the plateau. Compounds: heavy triples if
     * they've been living high-rep, a volume block if they've been grinding
     * heavy, speed work under a power emphasis. Isolation movements stay in
     * sane rep ranges — the wave shifts weight/reps inside the range instead.
     */
    private fun plateauWave(
        last: LiftSession,
        bestE1rm: Double,
        stalled: Int,
        settings: WorkoutPlanSettings,
        stepKg: Double,
        isCompound: Boolean,
        lastWorkingWeight: Double,
    ): LiftPrescription {
        val recentReps = last.averageReps ?: 8.0
        if (!isCompound) {
            // Isolation: never below 6 reps. Alternate ends of the range.
            return if (recentReps >= settings.hypRepMax - 1) {
                val heavier = (lastWorkingWeight + stepKg).roundToStep(stepKg)
                LiftPrescription(
                    sets = 4,
                    reps = settings.hypRepMin.coerceAtLeast(6),
                    weightKg = heavier,
                    intent = PrescriptionIntent.INTENSITY_WAVE,
                    rationale = "Stalled at the top of the range for $stalled sessions — go ${heavier.formatKg()} for strict ${settings.hypRepMin.coerceAtLeast(6)}s. Isolation stays out of singles territory.",
                )
            } else {
                val lighter = (lastWorkingWeight * 0.9).roundToStep(stepKg)
                LiftPrescription(
                    sets = 4,
                    reps = settings.hypRepMax,
                    weightKg = lighter,
                    intent = PrescriptionIntent.VOLUME_WAVE,
                    rationale = "Stalled for $stalled sessions — drop to ${lighter.formatKg()} and chase crisp ${settings.hypRepMax}s to restart the climb.",
                )
            }
        }
        if (settings.emphasis.dominant == EmphasisKind.POWER) {
            val speedWeight = (bestE1rm * 0.65).roundToStep(stepKg)
            return LiftPrescription(
                sets = 6,
                reps = 3,
                weightKg = speedWeight,
                intent = PrescriptionIntent.SPEED_WAVE,
                rationale = "Plateau after $stalled sessions — 6×3 at ${speedWeight.formatKg()} moved as fast as possible. Speed work, leave the grind at home.",
            )
        }
        return if (recentReps >= 6.5) {
            // Been doing 8s/10s → heavy low-rep wave.
            val heavyWeight = weightForReps(bestE1rm, 3).times(0.97).roundToStep(stepKg)
            LiftPrescription(
                sets = 5,
                reps = 3,
                weightKg = heavyWeight,
                intent = PrescriptionIntent.INTENSITY_WAVE,
                rationale = "Stalled at ${recentReps.roundToInt()}s for $stalled sessions — switching to heavy 5×3 at ${heavyWeight.formatKg()} to drive intensity, then we return to volume stronger.",
            )
        } else {
            // Been grinding heavy → volume wave at ~72%.
            val volumeWeight = (bestE1rm * 0.72).roundToStep(stepKg)
            LiftPrescription(
                sets = 4,
                reps = settings.hypRepMin,
                weightKg = volumeWeight,
                intent = PrescriptionIntent.VOLUME_WAVE,
                rationale = "Heavy work has stalled for $stalled sessions — 4×${settings.hypRepMin} at ${volumeWeight.formatKg()} builds the base before the next push.",
            )
        }
    }

    /** Which protocol an exercise runs under. */
    internal fun treatmentFor(
        exerciseCategory: String,
        isCompound: Boolean,
        role: ProgressionRole,
        settings: WorkoutPlanSettings,
        templateReps: Int?,
    ): LiftTreatment = when {
        exerciseCategory.equals("power", ignoreCase = true) -> LiftTreatment.POWER
        !isCompound -> LiftTreatment.HYPERTROPHY
        role == ProgressionRole.PRIMARY || role == ProgressionRole.SECONDARY -> when {
            // Template written for low reps, or the plan chases strength →
            // classic linear strength work on the big lifts.
            (templateReps != null && templateReps <= 6) -> LiftTreatment.STRENGTH
            settings.emphasis.dominant == EmphasisKind.STRENGTH -> LiftTreatment.STRENGTH
            settings.emphasis.dominant == EmphasisKind.POWER -> LiftTreatment.STRENGTH
            else -> LiftTreatment.HYPERTROPHY
        }
        else -> LiftTreatment.HYPERTROPHY
    }

    /** Sets × reps for a treatment, template targets winning where sane. */
    private fun schemeFor(
        treatment: LiftTreatment,
        settings: WorkoutPlanSettings,
        templateSets: Int?,
        templateReps: Int?,
    ): Pair<Int, Int> = when (treatment) {
        LiftTreatment.POWER -> (templateSets ?: settings.powerSets) to (templateReps ?: settings.powerRepMin)
        LiftTreatment.STRENGTH -> (templateSets ?: settings.strengthSets) to (templateReps ?: settings.strengthReps)
        LiftTreatment.HYPERTROPHY -> (templateSets ?: 4) to (templateReps?.coerceIn(settings.hypRepMin, settings.hypRepMax) ?: settings.hypRepMin)
    }

    /**
     * Rep-based double progression for bodyweight movements: when every set of
     * the last session matched its best set, the target climbs by the
     * accessory rep step; until then, the goal is closing the gap. Weight is
     * always null — there is no bar.
     */
    private fun prescribeBodyweight(
        history: LiftHistory,
        scheme: Pair<Int, Int>,
        settings: WorkoutPlanSettings,
        templateReps: Int?,
    ): LiftPrescription {
        val repSessions = history.sessions.filter { s -> s.sets.any { it.reps != null } }
        if (repSessions.isEmpty()) {
            return LiftPrescription(
                sets = scheme.first,
                reps = templateReps ?: scheme.second,
                weightKg = null,
                intent = PrescriptionIntent.START,
                rationale = "No history for ${history.exerciseName} yet — pick a rep count you can finish on every set.",
            )
        }
        val lastReps = repSessions.first().sets.mapNotNull { it.reps }
        val best = lastReps.max()
        val everySetHitBest = lastReps.all { it >= best }
        val repStep = settings.accessoryRepStep.coerceAtLeast(1)
        return if (everySetHitBest) {
            LiftPrescription(
                sets = scheme.first,
                reps = best + repStep,
                weightKg = null,
                intent = PrescriptionIntent.PROGRESS,
                rationale = "Every set hit $best last time — target ${best + repStep} across the board.",
            )
        } else {
            LiftPrescription(
                sets = scheme.first,
                reps = best,
                weightKg = null,
                intent = PrescriptionIntent.HOLD,
                rationale = "Best set was $best — bring every set up to it, then the target climbs.",
            )
        }
    }

    /** Minimum reps across the last session's working sets (warmups excluded). */
    internal fun workingSetMinReps(session: LiftSession): Int? {
        val top = session.sets.mapNotNull { it.weight }.maxOrNull() ?: return session.sets.mapNotNull { it.reps }.minOrNull()
        return session.sets
            .filter { (it.weight ?: 0.0) >= top * 0.85 }
            .mapNotNull { it.reps }
            .minOrNull()
    }

    /**
     * Number of consecutive recent sessions that failed to set a new
     * estimated-1RM (with a 0.5% tolerance so rounding noise doesn't count as
     * progress). 0 means the most recent session was a PR.
     */
    fun stalledSessionCount(sessions: List<LiftSession>): Int {
        if (sessions.size < 2) return 0
        var stalled = 0
        for (i in sessions.indices) {
            val current = sessions[i].bestE1rm ?: continue
            val older = sessions.drop(i + 1).mapNotNull { it.bestE1rm }.maxOrNull() ?: break
            if (current > older * 1.005) break
            stalled++
        }
        return stalled
    }

    /**
     * The "working weight" of a session: the most common weight among its
     * heavier sets — i.e. ignore warmups. Falls back to the top weight.
     */
    fun workingWeight(session: LiftSession): Double? {
        val weights = session.sets.mapNotNull { it.weight }.filter { it > 0 }
        if (weights.isEmpty()) return null
        val top = weights.max()
        val working = weights.filter { it >= top * 0.85 }
        return working.groupingBy { it }.eachCount().maxByOrNull { it.value * 1000 + it.key }?.key ?: top
    }

    // ───────────────────────── Running ─────────────────────────

    /**
     * Prescribe the next run from recent history + goals.
     *
     * [recentRuns] is newest-first. [goalTime] is a RUN_TIME goal (distance +
     * duration); [goalDistanceKm] is a RUN_DISTANCE goal; [weeklyKmTarget]
     * caps how much we pile on in any 7-day window.
     */
    fun prescribeRun(
        recentRuns: List<RunRecord>,
        goalTime: TrainingGoal? = null,
        goalDistanceKm: Double? = null,
        weeklyKmTarget: Double? = null,
        today: LocalDate = LocalDate.now(),
    ): RunPrescription {
        val runs = recentRuns.filter { it.distanceKm > 0 }
        val medianPace = runs.mapNotNull { it.paceSecondsPerKm }.sorted().let {
            when {
                it.isEmpty() -> null
                it.size % 2 == 1 -> it[it.size / 2]
                else -> (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
            }
        }
        val longestRecent = runs.take(10).maxOfOrNull { it.distanceKm } ?: 0.0

        // First run ever → just get out the door.
        if (runs.isEmpty()) {
            return RunPrescription(
                kind = RunKind.EASY,
                distanceKm = 3.0,
                paceSecondsPerKm = null,
                rationale = "No runs logged yet — 3 easy kilometres to set a baseline.",
            )
        }

        // Weekly volume guard: if the last 7 days already meet the target,
        // anything extra should be genuinely easy.
        val last7Km = runs.filter { runDateWithinDays(it.date, today, 7) }.sumOf { it.distanceKm }
        if (weeklyKmTarget != null && weeklyKmTarget > 0 && last7Km >= weeklyKmTarget) {
            return RunPrescription(
                kind = RunKind.EASY,
                distanceKm = (weeklyKmTarget * 0.25).coerceIn(2.0, 5.0).roundTo1(),
                paceSecondsPerKm = medianPace?.plus(30)?.roundToInt(),
                rationale = "Already at ${last7Km.roundTo1()} km of your ${weeklyKmTarget.roundTo1()} km week — keep this one short and conversational.",
            )
        }

        // What the last 7 days are missing decides what's due, like a coach
        // planning a week: speed work first when chasing a time, then the
        // weekly long run, then easy miles fill the gaps.
        val kindsThisWeek = runs
            .filter { runDateWithinDays(it.date, today, 7) }
            .map { classifyRun(it, medianPace, longestRecent) }
        val speedDue = goalTime != null && kindsThisWeek.none { it == RunKind.INTERVALS || it == RunKind.TEMPO }
        val longDue = kindsThisWeek.none { it == RunKind.LONG }
        val nextKind = when {
            speedDue -> {
                // Alternate intervals and tempo: prescribe whichever was done
                // longer ago (history is newest-first, so a smaller index means
                // more recent).
                val allKinds = runs.map { classifyRun(it, medianPace, longestRecent) }
                val lastIntervals = allKinds.indexOf(RunKind.INTERVALS)
                val lastTempo = allKinds.indexOf(RunKind.TEMPO)
                when {
                    lastIntervals == -1 -> RunKind.INTERVALS
                    lastTempo == -1 -> RunKind.TEMPO
                    lastIntervals < lastTempo -> RunKind.TEMPO
                    else -> RunKind.INTERVALS
                }
            }
            longDue -> RunKind.LONG
            else -> RunKind.EASY
        }

        val goalPace = goalTime?.let { goal ->
            val d = goal.targetDistanceKm ?: return@let null
            val t = goal.targetDurationSeconds ?: return@let null
            if (d > 0) t / d else null
        }
        val goalLabel = goalTime?.let { g ->
            val d = g.targetDistanceKm
            val t = g.targetDurationSeconds
            if (d != null && t != null) "${d.roundTo1()} km in ${formatDuration(t)}" else null
        }

        return when (nextKind) {
            RunKind.INTERVALS -> {
                val pace = (goalPace?.minus(12) ?: medianPace?.minus(45))?.roundToInt()
                RunPrescription(
                    kind = RunKind.INTERVALS,
                    distanceKm = 5.0,
                    paceSecondsPerKm = pace,
                    rationale = buildString {
                        append("Speed day: 6 × 2 min hard")
                        if (pace != null) append(" (~${formatPace(pace.toDouble())})")
                        append(" with 2 min jog recoveries")
                        if (goalLabel != null) append(" — sharpening for $goalLabel")
                        append(".")
                    },
                )
            }
            RunKind.TEMPO -> {
                val pace = (goalPace?.plus(15) ?: medianPace?.minus(20))?.roundToInt()
                RunPrescription(
                    kind = RunKind.TEMPO,
                    distanceKm = 5.0,
                    paceSecondsPerKm = pace,
                    rationale = buildString {
                        append("Tempo: 20 minutes comfortably hard")
                        if (pace != null) append(" around ${formatPace(pace.toDouble())}")
                        if (goalLabel != null) append(" — teaches your legs to hold goal pace for $goalLabel")
                        append(".")
                    },
                )
            }
            RunKind.LONG -> {
                val target = nextLongRunKm(longestRecent, goalDistanceKm)
                RunPrescription(
                    kind = RunKind.LONG,
                    distanceKm = target,
                    paceSecondsPerKm = medianPace?.plus(30)?.roundToInt(),
                    rationale = buildString {
                        append("Long run: ${target.roundTo1()} km at a relaxed pace")
                        if (goalDistanceKm != null) append(" — building toward ${goalDistanceKm.roundTo1()} km")
                        append(". Slow is the point.")
                    },
                )
            }
            RunKind.EASY -> RunPrescription(
                kind = RunKind.EASY,
                distanceKm = (longestRecent * 0.6).coerceIn(3.0, 8.0).roundTo1(),
                paceSecondsPerKm = medianPace?.plus(30)?.roundToInt(),
                rationale = "Easy aerobic miles — recovery is where the adaptation happens.",
            )
        }
    }

    /** Long-run distance: +10% on the recent longest, capped by the goal. */
    private fun nextLongRunKm(longestRecent: Double, goalDistanceKm: Double?): Double {
        val base = if (longestRecent <= 0) 5.0 else longestRecent * 1.1
        val capped = goalDistanceKm?.let { base.coerceAtMost(it) } ?: base
        return capped.roundTo1()
    }

    /**
     * Best-effort classification of a past run for "what's due this week"
     * bookkeeping. Pace thresholds are generous (45/25 s/km under the median)
     * so ordinary day-to-day pace variation doesn't get mistaken for a
     * quality session.
     */
    fun classifyRun(run: RunRecord, medianPace: Double?, longestRecent: Double): RunKind = when {
        // "Long" is relative to the runner: near their recent longest AND above a
        // floor that scales down for short-distance runners (was a hard 6 km,
        // which turned every prescription into a long-run ramp for them).
        longestRecent > 0 && run.distanceKm >= longestRecent * 0.85 &&
            run.distanceKm >= minOf(6.0, longestRecent * 0.75).coerceAtLeast(3.0) -> RunKind.LONG
        medianPace != null && (run.paceSecondsPerKm ?: Double.MAX_VALUE) <= medianPace - 45 -> RunKind.INTERVALS
        medianPace != null && (run.paceSecondsPerKm ?: Double.MAX_VALUE) <= medianPace - 25 -> RunKind.TEMPO
        else -> RunKind.EASY
    }

    private fun runDateWithinDays(date: String, today: LocalDate, days: Long): Boolean = runCatching {
        ChronoUnit.DAYS.between(LocalDate.parse(date), today) in 0 until days
    }.getOrDefault(false)
}

// ───────────────────────── Goal progress ─────────────────────────

/**
 * Computed progress for one goal — everything a card needs to render: current
 * vs target, percent, and a projected achievement date from the recent trend.
 */
data class GoalProgress(
    val goal: TrainingGoal,
    val currentLabel: String = "",
    val targetLabel: String = "",
    val percent: Float = 0f,
    val detail: String = "",
    val etaLabel: String = "",
    /** Cadence goals: discrete count this period, for a tally/dots visual. */
    val currentCount: Int = 0,
    val targetCount: Int = 0,
) {
    val isAchieved: Boolean get() = goal.achievedAt.isNotBlank() || percent >= 1f
}

/**
 * Least-squares slope of e1RM (kg per day) over [points]; null when there's
 * not enough spread to fit a line. Used for "at this rate you'll hit your
 * goal around <date>" projections.
 */
fun trendSlopePerDay(points: List<Pair<LocalDate, Double>>): Double? {
    if (points.size < 3) return null
    val xs = points.map { it.first.toEpochDay().toDouble() }
    val ys = points.map { it.second }
    val xMean = xs.average()
    val yMean = ys.average()
    val denominator = xs.sumOf { (it - xMean) * (it - xMean) }
    if (denominator < 1e-6) return null
    val slope = xs.zip(ys).sumOf { (x, y) -> (x - xMean) * (y - yMean) } / denominator
    return if (slope.isFinite()) slope else null
}

/** Projected date when a metric trending at [slopePerDay] reaches [target]. */
fun projectEta(current: Double, target: Double, slopePerDay: Double?, today: LocalDate = LocalDate.now()): LocalDate? {
    if (slopePerDay == null) return null
    val gap = target - current
    if (gap <= 0) return today
    if (slopePerDay <= 1e-9) return null
    val days = (gap / slopePerDay).roundToInt()
    if (days > 365 * 3) return null // beyond ~3 years isn't a projection, it's a guess
    return today.plusDays(days.toLong())
}

// ───────────────────────── Small helpers ─────────────────────────

internal fun Double.roundToStep(stepKg: Double): Double {
    if (stepKg <= 0) return this
    val steps = (this / stepKg).roundToInt()
    val rounded = steps * stepKg
    return if (abs(rounded) < 1e-9) 0.0 else (rounded * 100).roundToInt() / 100.0
}

internal fun Double.roundTo1(): Double = (this * 10).roundToInt() / 10.0

internal fun Double.formatKg(): String = formatWeight(this)
