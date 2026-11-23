package com.liliang.app.data

import com.liliang.app.engine.TrainingEmphasis

// Domain models, enums, and value objects for the app.
//
// This file holds *only* immutable shapes — data classes and enums — that the rest of
// the codebase passes around. Pure formatting helpers live in WorkoutFormat.kt; pure
// parsing helpers live in WorkoutParse.kt. Keeping data shapes separate from the
// procedural helpers makes it easy to reason about what's a value vs. what's a
// transformation, and makes future moves toward a typed-result / sealed-error model
// (see plan items D2 and M1-M5) much smaller diffs.

enum class ImportTarget(val label: String) {
    // User-facing labels — internal table names ("lifts_new") have been replaced with
    // names a non-developer can parse. The enum identifiers and DB tables they back
    // are unchanged, so existing code paths still work.
    FULL_DATABASE("Full database"),
    LIFTS_NEW_MERGE("Merge workout history"),
    LIFTS_NEW_REPLACE("Replace workout history"),
    EXERCISES("Import exercises"),
    COMBOS("Import combos"),
    WORKOUTS("Import workouts"),
    WORKOUT_PLANS("Import workout plans"),
}

enum class LogFormat(val dbValue: String) {
    REPS("reps"),
    SECONDS("seconds"),
    MINUTES("minutes");

    companion object {
        fun fromDb(value: String?): LogFormat = entries.firstOrNull { it.dbValue == value } ?: REPS
    }
}

/** What kinds of training happened on one day, for the heatmap's colouring. */
data class DayActivity(
    val sets: Int = 0,
    val hasLift: Boolean = false,
    val hasRun: Boolean = false,
    val hasMartialArts: Boolean = false,
)

data class AttendanceKpis(
    val last60Days: Int = 0,
    val thisYear: Int = 0,
    val lastYearTotal: Int = 0,
    val lastYearToDate: Int = 0,
    /**
     * Per-day session counts for roughly the last year, keyed by ISO date
     * string. Used by the dashboard heatmap (one cell per day) and sparkline
     * (rolled up by week). Days with zero workouts are absent from the map —
     * UI code defaults missing keys to zero.
     */
    val dailyHistory: Map<String, Int> = emptyMap(),
    /** Per-day activity composition over the same window, for heatmap colours. */
    val dailyActivity: Map<String, DayActivity> = emptyMap(),
)

data class RunningKpis(
    val last30DaysDistanceKm: Double = 0.0,
    val thisYearRuns: Int = 0,
    val thisYearDistanceKm: Double = 0.0,
    val longestRunKm: Double = 0.0,
    val fastestPaceSecondsPerKm: Double? = null,
    val averageRunKm: Double = 0.0,
    val recentDistanceHistory: List<ProgressPoint> = emptyList(),
    val lastThreeLongRuns: List<RunRecord> = emptyList(),
)

data class MartialArtsDisciplineKpis(
    val title: String = "",
    val last30DaysMinutes: Double = 0.0,
    val thisYearSessions: Int = 0,
    val thisYearMinutes: Double = 0.0,
    val longestRoundSeconds: Int? = null,
    val topLabel: String = "",
    val averageSessionMinutes: Double = 0.0,
    val recentMinutesHistory: List<ProgressPoint> = emptyList(),
)

data class MartialArtsKpis(
    val taiChi: MartialArtsDisciplineKpis = MartialArtsDisciplineKpis(title = "Tai Chi"),
    val kickboxing: MartialArtsDisciplineKpis = MartialArtsDisciplineKpis(title = "Kickboxing"),
    /** Distinct martial training days this year across ALL disciplines — the
     *  dashboard count. Custom arts (the Kendo case) count too. */
    val totalThisYearSessions: Int = 0,
)

data class ProgressPoint(
    val date: String = "",
    val value: Double = 0.0,
)

data class RunRecord(
    val date: String = "",
    val distanceKm: Double = 0.0,
    val durationSeconds: Int? = null,
    val paceSecondsPerKm: Double? = null,
)

data class LiftMaxKpi(
    val exerciseName: String = "",
    val maxWeight: Double? = null,
    val maxSetWeight: Double? = null,
    val maxSetReps: Int? = null,
    val maxSetVolume: Double? = null,
    val lastAppearanceDate: String = "",
    val progression: List<ProgressPoint> = emptyList(),
)

data class WeightPoint(
    val date: String = "",
    val weightKg: Double = 0.0,
)

data class MuscleHitKpi(
    val muscle: String = "",
    val sets: Int = 0,
)

data class CategorySplitKpi(
    val muscle: String = "",
    val strengthSets: Int = 0,
    val hypertrophySets: Int = 0,
)

data class TodayWorkoutSummary(
    val title: String = "",
    val subtitle: String = "",
    val actionLabel: String = "Open",
)

/** One (year, month) aggregate for the seasonality visuals. */
data class MonthValue(
    val year: Int = 0,
    val month: Int = 0,
    val value: Double = 0.0,
)

/** A new all-time estimated-1RM set within the recent window. */
data class PrRecord(
    val date: String = "",
    val exerciseName: String = "",
    val weight: Double? = null,
    val reps: Int? = null,
    val e1rm: Double = 0.0,
    /** Set for a timed-hold PR (longest ever) — weight/e1rm are meaningless then. */
    val durationSeconds: Int? = null,
)

data class DashboardKpis(
    val attendance: AttendanceKpis = AttendanceKpis(),
    val running: RunningKpis = RunningKpis(),
    val martialArts: MartialArtsKpis = MartialArtsKpis(),
    val featuredLifts: List<LiftMaxKpi> = emptyList(),
    val musclesHit: List<MuscleHitKpi> = emptyList(),
    val categorySplit30Days: List<CategorySplitKpi> = emptyList(),
    val categorySplitAllTime: List<CategorySplitKpi> = emptyList(),
    val bodyWeightHistory: List<WeightPoint> = emptyList(),
    val todayWorkout: TodayWorkoutSummary? = null,
    val recentPrs: List<PrRecord> = emptyList(),
    /** Training days per (year, month) across all history — the seasonality matrix. */
    val monthlyTrainingDays: List<MonthValue> = emptyList(),
    /** Average logged body weight per (year, month). */
    val monthlyBodyweight: List<MonthValue> = emptyList(),
    /** Distinct training days per (muscle, year, month), last 12 months — the
     *  balance grid ("is my pressing eating my pulling?"). */
    val muscleMonths: List<MuscleMonthValue> = emptyList(),
)

data class MuscleMonthValue(
    val muscle: String,
    val year: Int,
    val month: Int,
    val days: Int,
)

/**
 * How an exercise behaves under load, which decides what the progression
 * engine may prescribe for it:
 *
 *  - [COMPOUND]: multi-joint barbell/machine movements that respond to heavy
 *    low-rep work — eligible for strength schemes, heavy waves, test singles.
 *  - [ISOLATION]: single-joint or stabilising movements (flys, curls, roman
 *    chair…) that should live in moderate rep ranges — the engine never
 *    prescribes below 6 reps for these, and never a 1RM test.
 *  - [AUTO]: not set; the engine infers from the name (bench/squat/deadlift/
 *    press/row/pull-up/dip/lunge/clean/snatch read as compound, everything
 *    else as isolation).
 */
enum class MovementType(val dbValue: String?, val label: String) {
    AUTO(null, "Auto"),
    COMPOUND("compound", "Compound"),
    ISOLATION("isolation", "Isolation");

    companion object {
        fun fromDb(value: String?): MovementType =
            entries.firstOrNull { it.dbValue != null && it.dbValue == value } ?: AUTO
    }
}

/**
 * The unit the STACK is labelled in for one exercise — not a global preference.
 * Half the machines in Greg's gym are marked in pounds and half in kilos, and
 * some are marked in neither ("50" and nothing else), so the unit belongs to
 * the exercise, is set once at the machine, and then sticks.
 *
 * **Kilograms remain the only thing stored.** This chooses what the dial reads
 * and what gets typed into it; everything downstream — lifts_new, e1RM, PRs,
 * the progression engine, the folder ingest — sees kg and never learns that
 * pounds happened. KG is null in the DB so every pre-existing row reads as kg.
 */
enum class WeightUnit(val dbValue: String?, val label: String) {
    KG(null, "KG"),
    LB("lb", "LB");

    /** kg (stored) → the number to show on the dial. */
    fun fromKg(kg: Double): Double = if (this == LB) kg * LB_PER_KG else kg

    /** The number dialled → kg, which is what gets written. */
    fun toKg(value: Double): Double = if (this == LB) value / LB_PER_KG else value

    companion object {
        fun fromDb(value: String?): WeightUnit =
            entries.firstOrNull { it.dbValue != null && it.dbValue == value } ?: KG
    }
}

/** The exact international-pound definition, so lb → kg → lb round-trips clean. */
const val LB_PER_KG = 2.2046226218487757

data class ExerciseCatalogItem(
    val id: Long = 0L,
    val name: String,
    val defaultMuscle: String,
    val defaultCategory: String,
    val logFormat: LogFormat,
    val isBodyweight: Boolean,
    val movementType: MovementType = MovementType.AUTO,
    /** Role this exercise takes when added to a workout. NONE = decide from pools. */
    val defaultRole: ProgressionRole = ProgressionRole.NONE,
    /** What this machine's stack is labelled in. Display only — storage is kg. */
    val weightUnit: WeightUnit = WeightUnit.KG,
    /**
     * Done once on each side (side plank, half-split, single-leg balance). A
     * timed hold runs as a left/right pair and logs ONE row: one set means
     * both sides, so the Sunday set counts stay honest (R20). Set by the
     * folder ingest (`"perSide": true` in exercises.json), not on the phone.
     */
    val perSide: Boolean = false,
    val usageCount: Int = 0,
)

/** Name-based compound detection for exercises whose movement type is AUTO. */
fun inferIsCompound(name: String): Boolean {
    val n = name.lowercase()
    return listOf(
        "bench", "squat", "deadlift", "press", "row", "pull-up", "pull up", "pullup",
        "chin-up", "chin up", "chinup", "dip", "lunge", "clean", "snatch", "thrust",
        "rdl", "good morning", "push-up", "push up", "pushup",
    ).any { n.contains(it) }
}

/** Effective compound/isolation answer, honouring an explicit override. */
fun ExerciseCatalogItem?.isCompoundMovement(fallbackName: String = ""): Boolean = when (this?.movementType) {
    MovementType.COMPOUND -> true
    MovementType.ISOLATION -> false
    else -> inferIsCompound(this?.name ?: fallbackName)
}

data class ComboItem(
    val id: Long = 0L,
    val name: String,
    val discipline: String,
    val notes: String = "",
    /**
     * Ordered move sequence built in the combo builder. Persisted as a JSON
     * array of [ComboMove.id] strings in the `combos.moves_json` column. Empty
     * for legacy combos created before the builder existed; the UI treats an
     * empty list as "this combo has no per-move callouts" rather than "no
     * combo".
     */
    val moves: List<ComboMove> = emptyList(),
)

data class WorkoutSetRecord(
    val id: Long = 0L,
    val weight: Double? = null,
    val repsText: String? = null,
    val restSeconds: Int? = null,
    val durationSeconds: Int? = null,
    val distanceKm: Double? = null,
    val paceSecondsPerKm: Double? = null,
    val rpe: Int? = null,
    val notes: String = "",
    /** Epoch millis the set was logged (when recorded) — rest analysis reads
     *  the gaps between consecutive same-exercise sets. */
    val loggedAtMillis: Long? = null,
)

data class WorkoutEntry(
    val title: String,
    val category: String = "",
    val comboId: Long? = null,
    val discipline: String = "",
    val sets: List<WorkoutSetRecord> = emptyList(),
)

data class WorkoutGroup(
    val date: String,
    val workoutName: String,
    val entries: List<WorkoutEntry> = emptyList(),
    /** Span from first to last logged set, when set log-times are recorded. */
    val durationSeconds: Int? = null,
) {
    val displayName: String
        get() = workoutName.ifBlank { "Workout on $date" }
}

/**
 * Per-exercise context surfaced above the set logger so the user can pick a
 * sensible starting weight without trawling the history page.
 *
 * The original two-string design (`lastResult`/`bestResult`) collapsed an entire
 * previous session down to one set, which hides the very thing the user needs
 * to see. The new fields preserve the full last-session breakdown:
 *
 *   - [lastSessionDate]: ISO date of the most recent session this exercise
 *     appeared in. Blank if the exercise has no history.
 *   - [lastSessionSets]: every set logged on that date, in the order they
 *     happened. Use this to pick a working weight (typically the heaviest set
 *     where reps still hit target) — a single "best ever" reading lies when
 *     the user did a one-rep top-set followed by a back-off, or vice-versa.
 *
 * The legacy [lastResult] / [bestResult] strings are still populated for
 * backward compatibility with screens that haven't been updated to consume
 * the structured fields yet.
 */
data class PerformanceHint(
    val lastResult: String = "",
    val bestResult: String = "",
    val lastSessionDate: String = "",
    val lastSessionSets: List<WorkoutSetRecord> = emptyList(),
    /**
     * All-time best estimated 1RM for this exercise (Epley), or null if it has
     * no weighted history. Lìliàng's session screen compares the working set's
     * e1RM against this to fire the live-PR gold stamp.
     */
    val bestE1rm: Double? = null,
)

data class ExerciseHistorySummary(
    val exerciseName: String,
    val logFormat: LogFormat,
    val hint: PerformanceHint,
    val workoutCount: Int = 0,
    val totalSets: Int = 0,
    val lastPerformedDate: String = "",
    val bestWeight: Double? = null,
    val bestVolume: Double? = null,
    val recentWorkouts: List<WorkoutGroup> = emptyList(),
    /** Per-session best estimated 1RM, oldest first — the history chart. */
    val e1rmTrend: List<ProgressPoint> = emptyList(),
    /** For SECONDS/MINUTES exercises: the longest hold ever, and the per-date
     *  longest-hold trend (oldest first) — the record page's spine for holds. */
    val bestDurationSeconds: Int? = null,
    val durationTrend: List<ProgressPoint> = emptyList(),
)

data class WorkoutTemplateItem(
    val id: Long = 0L,
    val sortOrder: Int = 0,
    val exerciseName: String = "",
    val comboId: Long? = null,
    val comboName: String = "",
    val progressionRole: ProgressionRole = ProgressionRole.ACCESSORY,
    val setsTarget: Int? = null,
    val repsTarget: String = "",
    val weightTarget: Double? = null,
    val restTargetSeconds: Int? = null,
    val durationTargetSeconds: Int? = null,
    val distanceTargetKm: Double? = null,
    val paceTargetSecondsPerKm: Int? = null,
    val rpeTarget: Int? = null,
    val notes: String = "",
    val supersetId: String = "",
    val hints: PerformanceHint = PerformanceHint(),
) {
    val title: String
        get() = exerciseName.ifBlank { comboName }
}

data class WorkoutTemplate(
    val id: Long = 0L,
    val name: String = "",
    val discipline: String = "",
    val notes: String = "",
    val items: List<WorkoutTemplateItem> = emptyList(),
)

data class WorkoutSessionSet(
    val id: Long = 0L,
    val setIndex: Int = 1,
    val weight: Double? = null,
    val repsText: String = "",
    val comboId: Long? = null,
    val comboName: String = "",
    /**
     * A martial-arts round can run a *form* instead of a combo — [formId]
     * references the forms catalogue. On completion the row logs with the
     * form's name and discipline, and [formMoveIndex] ("practised up to move
     * N") writes a checkpoint that the Martial Arts hall picks up.
     */
    val formId: String = "",
    val formMoveIndex: Int? = null,
    val restSeconds: Int? = null,
    val durationSeconds: Int? = null,
    val distanceKm: Double? = null,
    val paceSecondsPerKm: Double? = null,
    val rpe: Int? = null,
    val notes: String = "",
    /**
     * Greg's own words about this set, spoken (or typed) in the gym: "grip
     * slipping on the way down", "took extra rest, phone call". Kept apart from
     * [notes], which carries the plan's cues, so the Sunday review can tell
     * the coach's voice from his. Posts to `lifts_new.Comment` on the seal.
     */
    val comment: String = "",
    /** Wall-clock epoch millis when this set was logged — drives the rest timer
     *  and the session's total time. Null for sets logged before this existed. */
    val loggedAtMillis: Long? = null,
)

data class WorkoutSessionItem(
    val id: Long = 0L,
    val sortOrder: Int = 0,
    val exerciseName: String = "",
    val comboId: Long? = null,
    val comboName: String = "",
    val progressionRole: ProgressionRole = ProgressionRole.ACCESSORY,
    val plannedSets: Int? = null,
    val targetReps: String = "",
    val targetWeight: Double? = null,
    val targetRestSeconds: Int? = null,
    val targetDurationSeconds: Int? = null,
    val targetDistanceKm: Double? = null,
    val targetPaceSecondsPerKm: Int? = null,
    val targetRpe: Int? = null,
    val notes: String = "",
    val supersetId: String = "",
    /**
     * One-sentence explanation from the progression engine of why this item's
     * targets are what they are ("Stalled at 8s for 3 sessions — heavy 5×3…").
     * Persisted with the session so a reopened draft still shows its reasoning.
     * Blank for items the engine didn't prescribe.
     */
    val coachNote: String = "",
    val hints: PerformanceHint = PerformanceHint(),
    val loggedSets: List<WorkoutSessionSet> = emptyList(),
    /**
     * A note recorded for the set he is ON, which does not exist until he
     * presses Log set. It rides here and moves onto that set when it is
     * logged, so a note said mid-set-2 lands on set 2, and one said while
     * resting after set 2 lands on set 3, the set that rest belongs to.
     * Whatever is still here at the seal goes onto the last logged set
     * ("after set 3: …"); on an exercise never logged it stays on the item
     * row, which is how a skipped exercise keeps its reason.
     */
    val pendingComment: String = "",
) {
    val title: String
        get() = exerciseName.ifBlank { comboName }
}

/** Joins spoken notes into one line, dropping blanks: "a · b". */
fun joinComments(vararg parts: String): String =
    parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * The seal's last word on a pending note: onto the last logged set, marked
 * "after set N", so a note said after the final set is not lost. An item with
 * no logged sets keeps its note where it is.
 */
fun WorkoutSessionItem.settlePendingComment(): WorkoutSessionItem {
    if (pendingComment.isBlank() || loggedSets.isEmpty()) return this
    val last = loggedSets.last()
    val settled = last.copy(comment = joinComments(last.comment, "after set ${last.setIndex}: ${pendingComment.trim()}"))
    return copy(loggedSets = loggedSets.dropLast(1) + settled, pendingComment = "")
}

/**
 * One exercise's place in a superset: [letter] A, B, C… in order of first
 * appearance, [index] for the colour, [partners] the other exercises in it.
 */
data class SupersetTag(val letter: Char, val index: Int, val partners: List<String>)

/**
 * Superset groups for a session's running order, keyed by item id. A group is
 * items sharing a non-blank `supersetId`; a lone id (its partner removed) is
 * not a superset and gets no tag.
 */
fun supersetTags(items: List<WorkoutSessionItem>): Map<Long, SupersetTag> {
    val groups = items.filter { it.supersetId.isNotBlank() }
        .groupBy { it.supersetId.trim() }
        .filterValues { it.size >= 2 }
    if (groups.isEmpty()) return emptyMap()
    val order = items.map { it.supersetId.trim() }.filter { it in groups }.distinct()
    val tags = mutableMapOf<Long, SupersetTag>()
    order.forEachIndexed { index, id ->
        val members = groups.getValue(id)
        members.forEach { item ->
            tags[item.id] = SupersetTag(
                letter = 'A' + (index % 26),
                index = index,
                partners = members.filter { it.id != item.id }.map { it.title.ifBlank { "Exercise" } },
            )
        }
    }
    return tags
}

private val PER_SIDE_TEXT = Regex("""(/\s*side|\b(per|each)\s+side)""", RegexOption.IGNORE_CASE)

/**
 * Does this item run once on each side? The exercise's own flag first; else a
 * plan that says so in the reps ("20s/side", "10 each side"), which is how
 * older plans wrote it.
 */
fun isPerSide(item: WorkoutSessionItem, exercise: ExerciseCatalogItem?): Boolean =
    exercise?.perSide == true || PER_SIDE_TEXT.containsMatchIn(item.targetReps)

/**
 * Lifecycle of a workout session. Sessions start as [DRAFT] (the user is logging
 * sets), then graduate to [COMPLETED] once they tap Finish — at that point the sets
 * are also flushed into the historical lifts_new table.
 *
 * Stored on disk as the raw [dbValue] strings, which match the values used since
 * the schema was first defined; [fromDb] is the lenient reader that accepts any
 * unknown string as DRAFT to keep older databases from blowing up after a future
 * status is added.
 */
enum class SessionStatus(val dbValue: String) {
    DRAFT("draft"),
    COMPLETED("completed");

    companion object {
        fun fromDb(value: String?): SessionStatus =
            entries.firstOrNull { it.dbValue == value } ?: DRAFT
    }
}

data class WorkoutSession(
    val id: Long = 0L,
    val name: String = "",
    val workoutDate: String = "",
    val sourceTemplateId: Long? = null,
    val status: SessionStatus = SessionStatus.DRAFT,
    val notes: String = "",
    val mood: String = "",
    val diet: String = "",
    val bodyWeightKg: Double? = null,
    /** Epoch millis the session was started and finished — total gym time. */
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val items: List<WorkoutSessionItem> = emptyList(),
)

// One word per role, used verbatim by every screen. NONE reads "Auto" because
// that's what it means: no explicit role, the engine decides from the pools.
enum class ProgressionRole(val dbValue: String, val label: String) {
    NONE("none", "Auto"),
    PRIMARY("primary", "Primary"),
    SECONDARY("secondary", "Secondary"),
    ACCESSORY("accessory", "Accessory"),
    RUNNING("running", "Running");

    companion object {
        fun fromDb(value: String?): ProgressionRole =
            entries.firstOrNull { it.dbValue == value } ?: NONE
    }
}

enum class PlanDayType(val dbValue: String, val label: String) {
    TEMPLATE("template", "Template"),
    CUSTOM("custom", "New workout"),
    REST("rest", "Rest day");

    companion object {
        fun fromDb(value: String?): PlanDayType =
            entries.firstOrNull { it.dbValue == value } ?: TEMPLATE
    }
}

/**
 * THE date→plan-slot mapping — Monday-anchored (dayIndex 0 = Monday, week
 * counting from the Monday of the start week) and repeatWeekly-aware. Shared
 * by the recommendation painter and the day sheet's plan editors so an edit
 * always lands in the slot the calendar painted; a second start-anchored copy
 * of this arithmetic once edited the wrong weekday on non-Monday plans and
 * refused rolling plans entirely.
 */
fun planSlotFor(plan: WorkoutPlan, target: java.time.LocalDate): WorkoutPlanDay? {
    if (plan.startDate.isBlank()) return null
    val start = runCatching { java.time.LocalDate.parse(plan.startDate) }.getOrNull() ?: return null
    if (target.isBefore(start)) return null
    val startMonday = start.with(java.time.DayOfWeek.MONDAY)
    val targetMonday = target.with(java.time.DayOfWeek.MONDAY)
    var weekIndex = java.time.temporal.ChronoUnit.WEEKS.between(startMonday, targetMonday).toInt()
    if (weekIndex < 0) return null
    if (plan.repeatWeekly) {
        weekIndex %= plan.weeks.coerceAtLeast(1)
    } else if (weekIndex >= plan.weeks) {
        return null
    }
    val dayIndex = target.dayOfWeek.value - 1
    return plan.days.firstOrNull { it.weekIndex == weekIndex && it.dayIndex == dayIndex }
}

data class WorkoutPlanSettings(
    val primaryWeightStepKg: Double = 2.5,
    val secondaryWeightStepKg: Double = 2.5,
    val accessoryRepStep: Int = 1,
    val accessorySetStep: Int = 0,
    /**
     * Relative emphasis the progression engine gives each training quality.
     * Compared relatively (they needn't sum to 100); the dominant one picks
     * default rep schemes and plateau-wave flavour.
     */
    val emphasisStrength: Int = 40,
    val emphasisHypertrophy: Int = 40,
    val emphasisPower: Int = 20,
    /** Weekly running volume the engine plans toward; null = no running focus. */
    val weeklyRunKmTarget: Double? = null,
    // Engine tunables — the dials behind the prescriptions.
    /** Hypertrophy double-progression rep range (reps climb min→max, then weight up). */
    val hypRepMin: Int = 8,
    val hypRepMax: Int = 12,
    /** Strength scheme for compound primaries under a strength emphasis. */
    val strengthSets: Int = 5,
    val strengthReps: Int = 5,
    /** Power-category protocol: sets × repMin–repMax at percent of e1RM, moved fast. */
    val powerSets: Int = 3,
    val powerRepMin: Int = 5,
    val powerRepMax: Int = 8,
    val powerPercent: Int = 50,
    /** Sessions without an e1RM PR before the engine forces a light week. */
    val deloadAfterStalls: Int = 6,
    /** Days since an exercise was last trained before "rebuild" mode replaces stall logic. */
    val layoffDays: Int = 21,
) {
    val emphasis: TrainingEmphasis
        get() = TrainingEmphasis(emphasisStrength, emphasisHypertrophy, emphasisPower)
}

data class WorkoutPlanDay(
    val id: Long = 0L,
    val weekIndex: Int = 0,
    val dayIndex: Int = 0,
    val dayLabel: String = "",
    val dayType: PlanDayType = PlanDayType.REST,
    val templateId: Long? = null,
    val templateName: String = "",
    val customWorkoutName: String = "",
)

data class WorkoutPlan(
    val id: Long = 0L,
    val name: String = "",
    val startDate: String = "",
    val weeks: Int = 1,
    val isActive: Boolean = false,
    /** Loop the week grid forever instead of ending after [weeks]. */
    val repeatWeekly: Boolean = false,
    /** When false, sessions start from raw template targets — no engine prescriptions. */
    val useEngine: Boolean = true,
    val days: List<WorkoutPlanDay> = emptyList(),
)

data class PlanRecommendation(
    val planName: String = "",
    val dayLabel: String = "",
    val dayType: PlanDayType = PlanDayType.REST,
    val templateId: Long? = null,
    val templateName: String = "",
    val customWorkoutName: String = "",
    val date: String = "",
    /** Carried from the plan: whether sessions built from this slot get engine prescriptions. */
    val useEngine: Boolean = true,
)

enum class CalendarDayState {
    NONE,
    REST,
    SCHEDULED,
    FUTURE_SCHEDULED,
    DRAFT,
    COMPLETED,
}

enum class CalendarMarkerType {
    STRENGTH,
    HYPERTROPHY,
    MARTIAL_ARTS,
    RUNNING,
    MOBILITY,
}

data class CalendarDaySummary(
    val date: String = "",
    val dayNumber: Int = 0,
    val inCurrentMonth: Boolean = true,
    val isToday: Boolean = false,
    val state: CalendarDayState = CalendarDayState.NONE,
    val workoutName: String = "",
    val markers: List<CalendarMarkerType> = emptyList(),
    val overflowCount: Int = 0,
)

/** Roll-up of a calendar month's training, shown under the month header. */
data class MonthStats(
    val trainingDays: Int = 0,
    val liftSets: Int = 0,
    val runKm: Double = 0.0,
    val martialArtsMinutes: Int = 0,
)

data class CalendarMonthSnapshot(
    val monthLabel: String = "",
    val days: List<CalendarDaySummary> = emptyList(),
    val stats: MonthStats = MonthStats(),
)

data class DayWorkoutSnapshot(
    val date: String = "",
    val recommendation: PlanRecommendation? = null,
    val draftSession: WorkoutSession? = null,
    /** Same-day drafts beyond [draftSession] — parked, but not invisible. */
    val extraDraftCount: Int = 0,
    val completedWorkouts: List<WorkoutGroup> = emptyList(),
)

data class PlannerSnapshot(
    val templates: List<WorkoutTemplate> = emptyList(),
    val recentWorkouts: List<WorkoutGroup> = emptyList(),
    val frequentExercises: List<ExerciseCatalogItem> = emptyList(),
)

data class ConfigSnapshot(
    val exercises: List<ExerciseCatalogItem> = emptyList(),
    val combos: List<ComboItem> = emptyList(),
    val muscleGroups: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val disciplines: List<String> = emptyList(),
    val muscleGroupRules: List<MuscleGroupRule> = emptyList(),
    val autoBackupFolderLabel: String = "",
    /** Outcome of the most recent auto-backup attempt; null = never attempted. */
    val lastBackup: BackupStatus? = null,
    /** Ingest folder (definitions flowing IN), blank = not configured. */
    val ingestFolderLabel: String = "",
    /** Outcome of the most recent folder ingest; null = never attempted. */
    val lastIngest: BackupStatus? = null,
    val plans: List<WorkoutPlan> = emptyList(),
    val planSettings: WorkoutPlanSettings = WorkoutPlanSettings(),
    val dashboardFeaturedExercises: List<String> = emptyList(),
    /** Form ids tiling the dashboard; null = automatic (forms in progress). */
    val dashboardFeaturedFormIds: List<String>? = null,
    /** Master switch for engine prescriptions, coach notes, previews, run tips. */
    val coachEnabled: Boolean = true,
    /** One tick + buzz when the rest count-up crosses the plan's rest. */
    val restChimeEnabled: Boolean = true,
    val goals: List<TrainingGoal> = emptyList(),
)

/** Result of the most recent auto-backup attempt — surfaced in Settings. */
data class BackupStatus(
    val timeMillis: Long,
    val ok: Boolean,
    /** Snapshot filename on success; short error description on failure. */
    val detail: String,
)

/** Outcome of one folder-ingest pass — what got applied, what went wrong. */
data class IngestResult(
    val applied: Int = 0,
    /** True when every watched file's hash matched the last applied pass. */
    val upToDate: Boolean = false,
    val detail: String = "",
    val errors: List<String> = emptyList(),
)

data class MuscleGroupRule(
    val id: Long = 0L,
    val muscleGroup: String = "",
    val role: ProgressionRole = ProgressionRole.NONE,
    val exerciseName: String = "",
)

data class DashboardSnapshot(
    val kpis: DashboardKpis = DashboardKpis(),
    val exercises: List<ExerciseCatalogItem> = emptyList(),
    val selectedExerciseSummary: ExerciseHistorySummary? = null,
)

/**
 * Whether this exercise's logging UI should expose combo selection. Currently scoped to
 * the two martial-arts heavy-bag/shadowboxing exercises that pair with stored combos.
 */
fun ExerciseCatalogItem.supportsCombos(): Boolean {
    val normalized = name.trim().lowercase()
    return normalized == "heavy bag" || normalized == "shadowboxing"
}

// ───────────────────────── Training goals ─────────────────────────

/**
 * What a goal is about. Each kind reads a different slice of history to
 * measure progress, and feeds the progression engine differently:
 *
 *  - [LIFT_TARGET]: hit a weight on a named exercise. Shapes that lift's
 *    trajectory and triggers test days when the estimated 1RM closes in.
 *  - [RUN_TIME]: cover a distance in a time ("5 km in 25:00"). Pulls interval
 *    and tempo work into run prescriptions at goal-derived paces.
 *  - [RUN_DISTANCE]: complete a distance. Grows the long run toward it.
 *  - [BODYWEIGHT]: reach a body weight (either direction), measured from the
 *    logged MYWEIGHT series.
 *  - [DISCIPLINE_SESSIONS]: practice a discipline N times a week ("more Chen
 *    style"). Measured from logged martial-arts sessions; nudges scheduling.
 *  - [FORM_MASTERY]: finish learning a martial-arts form, measured from the
 *    form progress tracker.
 *  - [INSPIRATION]: words to train by. No metrics — shown where you'll see it.
 */
enum class GoalKind(val dbValue: String, val label: String) {
    LIFT_TARGET("lift_target", "Lift a weight"),
    RUN_TIME("run_time", "Run a time"),
    RUN_DISTANCE("run_distance", "Run a distance"),
    BODYWEIGHT("bodyweight", "Body weight"),
    /** Train N times per week or month — all workouts or just runs. */
    CADENCE("cadence", "Train N times"),
    DISCIPLINE_SESSIONS("discipline_sessions", "Practice cadence"),
    FORM_MASTERY("form_mastery", "Master a form"),
    INSPIRATION("inspiration", "Inspiration");

    companion object {
        fun fromDb(value: String?): GoalKind = entries.firstOrNull { it.dbValue == value } ?: INSPIRATION
    }
}

/** Scope of a [GoalKind.CADENCE] goal — what counts toward it. */
enum class CadenceScope(val dbValue: String, val label: String) {
    ALL("all", "Any workout"),
    RUNNING("running", "Runs");

    companion object {
        fun fromDb(value: String?): CadenceScope = entries.firstOrNull { it.dbValue == value } ?: ALL
    }
}

/** Window of a [GoalKind.CADENCE] goal. */
enum class CadencePeriod(val dbValue: String, val label: String, val days: Int) {
    WEEK("week", "Per week", 7),
    MONTH("month", "Per month", 30);

    companion object {
        fun fromDb(value: String?): CadencePeriod = entries.firstOrNull { it.dbValue == value } ?: WEEK
    }
}

data class TrainingGoal(
    val id: Long = 0L,
    val kind: GoalKind = GoalKind.INSPIRATION,
    /** User-facing name — "100 kg incline bench", "Five strict pull-ups". */
    val title: String = "",
    val exerciseName: String = "",
    val discipline: String = "",
    val formId: String = "",
    val targetWeightKg: Double? = null,
    val targetDistanceKm: Double? = null,
    val targetDurationSeconds: Int? = null,
    /** Count for CADENCE / DISCIPLINE_SESSIONS goals — N times per period. */
    val targetSessionsPerWeek: Int? = null,
    /** CADENCE only — what counts toward the goal. */
    val cadenceScope: CadenceScope = CadenceScope.ALL,
    /** CADENCE only — the window the count resets over. */
    val cadencePeriod: CadencePeriod = CadencePeriod.WEEK,
    val targetDate: String = "",
    val notes: String = "",
    val createdAt: String = "",
    val achievedAt: String = "",
) {
    val isAchieved: Boolean get() = achievedAt.isNotBlank()
}

// ───────────────────────── Form checkpoints ─────────────────────────

/**
 * "On this date I practised form X up to move N." Logged from a Tai Chi /
 * martial-arts session so the user doesn't have to remember where in the form
 * they got to last time. Distinct from martial_arts_form_progress, which
 * tracks which moves are *learned* — a checkpoint records how far a single
 * session went.
 */
data class FormCheckpoint(
    val id: Long = 0L,
    val date: String = "",
    val formId: String = "",
    val moveIndex: Int = 0,
    val notes: String = "",
)

/**
 * One line of the "what the coach has planned" preview shown on a scheduled
 * day before the workout is opened: exercise title, the prescription in short
 * form ("5×3 @ 82.5 kg"), and the engine's rationale.
 */
data class CoachPreviewItem(
    val title: String = "",
    val prescription: String = "",
    val note: String = "",
)

// ───────────────────────── Vocabularies ─────────────────────────

/**
 * Canonical pick-list values for the fields that used to be free-text. The
 * dropdowns merge these with whatever already exists in the database so user
 * data is never hidden, but new entries get steered toward consistent values.
 */
object WorkoutVocab {
    val muscles: List<String> = listOf(
        "Chest", "Back", "Shoulders", "Arms", "Biceps", "Triceps", "Legs", "Abs", "Full-Body",
    )

    val categories: List<String> = listOf(
        "Strength", "Hypertrophy", "Power", "Mobility", "Conditioning", "Running", "Martial Arts",
    )

    val disciplines: List<String> = listOf(
        "Tai Chi", "Boxing", "Muay Thai", "Taekwondo", "Shaolin Kung Fu", "Kickboxing",
    )

    /** Merge canonical values with observed ones, preserving canonical order first. */
    fun merge(canonical: List<String>, observed: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        canonical.forEach { seen.add(it) }
        observed.map { it.trim() }.filter { it.isNotBlank() }.forEach { value ->
            if (seen.none { it.equals(value, ignoreCase = true) }) seen.add(value)
        }
        return seen.toList()
    }
}
