package com.liliang.app.data

import com.liliang.app.engine.*

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "WorkoutRepository"

private const val DATABASE_NAME = "workouts.db"

/**
 * Schema version for the on-device database.
 *
 * Migrations are dispatched by [WorkoutDatabaseHelper.runMigrations]. To introduce a schema
 * change, bump this constant and add a step to the migration list there. App-managed tables
 * (those defined in `ensureAppTables`) use idempotent `CREATE TABLE IF NOT EXISTS` /
 * `ALTER TABLE` patterns and do not require a migration entry — only changes that need to
 * mutate existing data or rebuild seed-side tables (`lifts_new`, `exercises`, `combos`)
 * need an explicit step.
 *
 * History:
 *  - v1: initial.
 *  - v2: defensive indexes on `lifts_new(Date)` / `exercises(name)` for databases imported
 *        from sources that were created without them.
 *  - v3: martial_arts_form_progress table — per-move "learned" state for the
 *        Tai Chi / kickboxing form tracker.
 *  - v4: combos.moves_json column — JSON array of ComboMove ids stored on each
 *        combo so the round timer can call out the move sequence via TTS.
 *  - v5: training_goals + martial_arts_form_checkpoints tables, coach_note on
 *        session items, and progression-emphasis columns on plan settings.
 *  - v6: martial_arts_forms / _form_moves / _disciplines tables (forms become
 *        user-editable data, seeded from the Kotlin catalogue), plan repeat /
 *        engine toggles, and exercises.movement_type for the engine.
 *  - v7: catalogue refresh — Xiao Hong Quan move list, the Qigong forms
 *        (Ba Duan Jin, Yi Jin Jing, Wu Qin Xi, Liu Zi Jue), yoga sun
 *        salutations, Qigong/Yoga disciplines, and Qigong/Yoga exercises.
 *  - v8: the Chen form's display name corrected to 陈氏太极拳一路 / Yi Lu
 *        (the id keeps its historical value so progress stays attached).
 */
private const val DATABASE_VERSION = 8
private const val SETTINGS_PREFS = "workout_settings"
private const val AUTO_BACKUP_TREE_URI_KEY = "auto_backup_tree_uri"
private const val INGEST_TREE_URI_KEY = "ingest_tree_uri"
private const val LAST_INGEST_TIME_KEY = "last_ingest_time_ms"
private const val LAST_INGEST_OK_KEY = "last_ingest_ok"
private const val LAST_INGEST_DETAIL_KEY = "last_ingest_detail"
private const val INGEST_HASH_KEY_PREFIX = "ingest_hash_"
private const val INGEST_SESSION_LEDGER_KEY = "ingest_applied_session_ids"
private const val LAST_BACKUP_TIME_KEY = "last_backup_time_ms"
private const val LAST_BACKUP_OK_KEY = "last_backup_ok"
private const val LAST_BACKUP_DETAIL_KEY = "last_backup_detail"
private const val DASHBOARD_FEATURED_EXERCISES_KEY = "dashboard_featured_exercises"
private const val DASHBOARD_FEATURED_FORMS_KEY = "dashboard_featured_forms"
private const val COACH_ENABLED_KEY = "coach_enabled"
private const val REST_CHIME_KEY = "rest_chime_enabled"
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

/**
 * Filename-safe sortable stamp used in auto-backup names. Sortable by string
 * comparison so the SAF folder listing reads chronologically; safe for filesystem
 * use because it has no colons or slashes.
 */
private val BACKUP_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/** Cap on the number of timestamped auto-backups retained in the SAF folder. */
private const val MAX_AUTO_BACKUPS = 5
private data class SessionDefaults(
    val diet: String = "",
    val bodyWeightKg: Double? = null,
)

private data class MartialArtsRow(
    val date: String,
    val exercise: String,
    val durationSeconds: Int?,
)

/**
 * Top-level data-access façade for the app. Marked `open` so that tests (and, in a
 * later refactor, focused service classes — see Phase 2.B2 in the improvement plan)
 * can subclass and override individual methods without forcing every caller through
 * a 38-method interface today.
 *
 * Construction is cheap: the underlying [SQLiteOpenHelper] is opened lazily on first
 * DB access via [ensureDatabaseReady], so tests can build this object on the main
 * thread without triggering disk I/O.
 */
open class WorkoutRepository(private val context: Context) {

    // Lazy open so we don't hit disk on the main thread when the ViewModel is constructed.
    // First DB access happens from a background dispatcher (see WorkoutAppViewModel.load).
    private var helper: WorkoutDatabaseHelper = createHelper()

    @Synchronized
    private fun createHelper(): WorkoutDatabaseHelper = WorkoutDatabaseHelper(context.applicationContext)

    @Synchronized
    private fun reopenHelper() {
        helper.close()
        helper = createHelper()
    }

    private fun ensureDatabaseReady() {
        helper.prepareSeedDatabase()
        helper.writableDatabase
    }

    // Dashboard KPI computation runs ~15-20 queries (attendance, running, martial arts,
    // featured-lift maxes, muscle hits, category split for two windows, body weight,
    // today summary). Caching the aggregate snapshot lets subsequent dashboard reads
    // (e.g. just opening the exercise detail panel) skip all of that work. The cache is
    // invalidated whenever a mutation touches data that could change a KPI — see the
    // invalidate*() callers below.
    //
    // Volatile because reads/writes happen from the IO dispatcher pool and we want a
    // freshly-published cached value to be visible to the next reader without locking.
    @Volatile
    private var cachedDashboardKpis: DashboardKpis? = null

    /**
     * Invalidate the cached dashboard KPI snapshot so the next dashboard load recomputes
     * it. Call from every mutation path that could change attendance, running, martial
     * arts, featured-lift, muscle-hit, category-split, body-weight, or today-summary
     * data. Cheap (a single field write); always safer to over-invalidate than under.
     */
    fun invalidateDashboardKpiCache() {
        cachedDashboardKpis = null
        cachedGoalProgress = null
        cachedHints.clear()
    }

    /**
     * Load the dashboard snapshot. Suspend so KPI computation can cooperate with
     * coroutine cancellation: if the user navigates away mid-load, [getDashboardKpis]
     * will throw CancellationException at the next checkpoint instead of finishing
     * a ~15-20-query batch only to have its result discarded.
     */
    suspend fun loadDashboard(query: String, selectedExerciseName: String?): DashboardSnapshot {
        val exercises = searchExercises(query)
        currentCoroutineContext().ensureActive()
        val selected = selectedExerciseName?.takeIf { it.isNotBlank() }
        return DashboardSnapshot(
            kpis = getDashboardKpis(),
            exercises = exercises,
            selectedExerciseSummary = selected?.let(::getExerciseHistorySummary),
        )
    }

    fun loadPlannerSnapshot(): PlannerSnapshot = PlannerSnapshot(
        templates = getTemplates(),
        // 12 rather than 6: the start-workout chooser dedupes by name before
        // showing its "recent" section, so over-fetch to survive duplicates.
        recentWorkouts = getRecentWorkoutGroups(limit = 12),
        frequentExercises = searchExercises(query = "", limit = 12),
    )

    fun getHintForExercise(exerciseName: String): PerformanceHint = getPerformanceHint(exerciseName)

    fun loadConfigSnapshot(): ConfigSnapshot = ConfigSnapshot(
        exercises = getAllExercises(),
        combos = getAllCombos(),
        muscleGroups = WorkoutVocab.merge(WorkoutVocab.muscles, getMuscleGroups()),
        categories = getCategories(),
        disciplines = getDisciplines(),
        muscleGroupRules = getMuscleGroupRules(),
        autoBackupFolderLabel = getAutoBackupFolderLabel(),
        lastBackup = getLastBackupStatus(),
        ingestFolderLabel = getIngestFolderLabel(),
        lastIngest = getLastIngestStatus(),
        plans = getWorkoutPlans(),
        planSettings = getWorkoutPlanSettings(),
        dashboardFeaturedExercises = getDashboardFeaturedExercises(),
        dashboardFeaturedFormIds = getDashboardFeaturedFormIds(),
        coachEnabled = isCoachEnabled(),
        restChimeEnabled = isRestChimeEnabled(),
        goals = getTrainingGoals(),
    )

    fun setAutoBackupFolder(uri: Uri?) {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (uri == null) remove(AUTO_BACKUP_TREE_URI_KEY) else putString(AUTO_BACKUP_TREE_URI_KEY, uri.toString())
        }.apply()
    }

    /**
     * Which forms tile the dashboard. Null = never chosen → sensible default
     * (forms with learning underway); empty list = user chose "none".
     */
    fun getDashboardFeaturedFormIds(): List<String>? {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(DASHBOARD_FEATURED_FORMS_KEY, null) ?: return null
        return raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun setDashboardFeaturedFormIds(ids: List<String>) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putString(DASHBOARD_FEATURED_FORMS_KEY, ids.joinToString("\n")).apply()
    }

    fun saveDashboardFeaturedExercises(names: List<String>) {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(
            DASHBOARD_FEATURED_EXERCISES_KEY,
            names.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"),
        ).apply()
        // The featured-exercise list drives the dashboard KPI snapshot's `featuredLifts`
        // field. Stored in SharedPreferences (no DB write), so no auto-backup needed,
        // but we still need to invalidate the cached KPIs.
        invalidateDashboardKpiCache()
    }

    fun getAutoBackupFolderLabel(): String {
        val treeUri = getAutoBackupTreeUri() ?: return ""
        // A revoked/deleted tree throws SecurityException from the provider —
        // that must degrade to a label, not take loadConfigSnapshot (and with it
        // the whole Settings room) down.
        return runCatching {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: "Configured" else "Configured"
            } ?: "Configured"
        }.getOrDefault("Folder unavailable — re-pick it")
    }

    fun getMuscleGroups(): List<String> {
        val items = mutableSetOf<String>()
        readableDb().rawQuery(
            """
            SELECT DISTINCT TRIM(COALESCE(Muscle, '')) AS muscle
            FROM lifts_new
            WHERE TRIM(COALESCE(Muscle, '')) != ''
            ORDER BY muscle
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.string("muscle")
            }
        }
        return items.toList().sortedBy { it.lowercase() }
    }

    fun getMuscleGroupRules(): List<MuscleGroupRule> {
        val items = mutableListOf<MuscleGroupRule>()
        readableDb().rawQuery(
            """
            SELECT id, muscle_group, role, exercise_name
            FROM workout_muscle_group_rules
            ORDER BY muscle_group, role, exercise_name
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += MuscleGroupRule(
                    id = cursor.long("id"),
                    muscleGroup = cursor.string("muscle_group"),
                    role = ProgressionRole.fromDb(cursor.string("role")),
                    exerciseName = cursor.string("exercise_name"),
                )
            }
        }
        return items
    }

    fun saveMuscleGroupRule(rule: MuscleGroupRule) {
        if (rule.muscleGroup.isBlank()) error("Muscle group required")
        if (rule.exerciseName.isBlank()) error("Exercise required")
        writableDb().insertWithOnConflict(
            "workout_muscle_group_rules",
            null,
            ContentValues().apply {
                put("id", rule.id.takeIf { it != 0L })
                put("muscle_group", rule.muscleGroup.trim())
                put("role", rule.role.dbValue)
                put("exercise_name", rule.exerciseName.trim())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        autoExportDatabaseIfConfigured()
    }

    fun deleteMuscleGroupRule(id: Long) {
        writableDb().delete("workout_muscle_group_rules", "id = ?", arrayOf(id.toString()))
        autoExportDatabaseIfConfigured()
    }

    fun ensureMuscleGroupPoolSeeded(muscleGroup: String, role: ProgressionRole) {
        val normalizedMuscle = muscleGroup.trim()
        if (normalizedMuscle.isBlank()) return
        val normalizedRole = role.dbValue
        if (normalizedRole.isBlank()) return
        val db = writableDb()
        val didSeed: Boolean
        db.beginTransaction()
        try {
            val alreadySeeded = db.rawQuery(
                """
                SELECT 1
                FROM workout_muscle_group_pool_state
                WHERE muscle_group = ? AND role = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(normalizedMuscle, normalizedRole),
            ).use { it.moveToFirst() }
            didSeed = !alreadySeeded
            if (!alreadySeeded) {
                getSeedExercisesForMuscleGroup(normalizedMuscle).forEach { exerciseName ->
                    db.insertWithOnConflict(
                        "workout_muscle_group_rules",
                        null,
                        ContentValues().apply {
                            put("muscle_group", normalizedMuscle)
                            put("role", normalizedRole)
                            put("exercise_name", exerciseName)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
                db.insertWithOnConflict(
                    "workout_muscle_group_pool_state",
                    null,
                    ContentValues().apply {
                        put("muscle_group", normalizedMuscle)
                        put("role", normalizedRole)
                        put("seeded_at", nowStamp())
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (didSeed) autoExportDatabaseIfConfigured()
    }

    fun addExerciseToMuscleGroupPool(muscleGroup: String, role: ProgressionRole, exerciseName: String) {
        // pool_state first, THEN the rule save (whose auto-export snapshots both).
        writableDb().insertWithOnConflict(
            "workout_muscle_group_pool_state",
            null,
            ContentValues().apply {
                put("muscle_group", muscleGroup.trim())
                put("role", role.dbValue)
                put("seeded_at", nowStamp())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        saveMuscleGroupRule(
            MuscleGroupRule(
                muscleGroup = muscleGroup.trim(),
                role = role,
                exerciseName = exerciseName.trim(),
            ),
        )
    }

    fun getSeedExercisesForMuscleGroup(muscleGroup: String): List<String> {
        val items = linkedSetOf<String>()
        readableDb().rawQuery(
            """
            SELECT DISTINCT TRIM(COALESCE(name, '')) AS exercise_name
            FROM exercises
            WHERE LOWER(TRIM(COALESCE(default_muscle, ''))) = LOWER(?)
              AND TRIM(COALESCE(name, '')) != ''
            ORDER BY exercise_name
            """.trimIndent(),
            arrayOf(muscleGroup.trim()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.string("exercise_name")
            }
        }
        readableDb().rawQuery(
            """
            SELECT DISTINCT TRIM(COALESCE(Exercise, '')) AS exercise_name
            FROM lifts_new
            WHERE LOWER(TRIM(COALESCE(Muscle, ''))) = LOWER(?)
              AND TRIM(COALESCE(Exercise, '')) != ''
            ORDER BY exercise_name
            """.trimIndent(),
            arrayOf(muscleGroup.trim()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.string("exercise_name")
            }
        }
        return items.toList().sortedBy { it.lowercase() }
    }

    fun getWorkoutPlans(database: SQLiteDatabase = readableDb()): List<WorkoutPlan> {
        val items = mutableListOf<WorkoutPlan>()
        database.rawQuery(
            """
            SELECT id, name, start_date, weeks, is_active,
                   COALESCE(repeat_weekly, 0) AS repeat_weekly, COALESCE(use_engine, 1) AS use_engine
            FROM workout_plans
            ORDER BY is_active DESC, name ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.long("id")
                items += WorkoutPlan(
                    id = id,
                    name = cursor.string("name"),
                    startDate = cursor.string("start_date"),
                    weeks = cursor.int("weeks"),
                    isActive = cursor.int("is_active") == 1,
                    repeatWeekly = cursor.int("repeat_weekly") == 1,
                    useEngine = cursor.int("use_engine") == 1,
                    days = loadWorkoutPlanDays(id, database),
                )
            }
        }
        return items
    }

    fun saveWorkoutPlan(plan: WorkoutPlan, autoExport: Boolean = true): Long {
        val database = writableDb()
        val planId: Long
        database.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("name", plan.name.trim())
                put("start_date", plan.startDate.trim())
                put("weeks", plan.weeks)
                put("is_active", if (plan.isActive) 1 else 0)
                put("repeat_weekly", if (plan.repeatWeekly) 1 else 0)
                put("use_engine", if (plan.useEngine) 1 else 0)
            }
            val existingId = plan.id.takeIf { it != 0L } ?: getWorkoutPlanIdByName(plan.name)
            planId = if (existingId == null) {
                database.insertOrThrow("workout_plans", null, values)
            } else {
                database.update("workout_plans", values, "id = ?", arrayOf(existingId.toString()))
                database.delete("workout_plan_days", "plan_id = ?", arrayOf(existingId.toString()))
                existingId
            }
            if (plan.isActive) {
                database.execSQL("UPDATE workout_plans SET is_active = 0 WHERE id != ?", arrayOf(planId))
            }
            plan.days.sortedWith(compareBy<WorkoutPlanDay> { it.weekIndex }.thenBy { it.dayIndex }).forEach { day ->
                database.insertOrThrow(
                    "workout_plan_days",
                    null,
                    ContentValues().apply {
                        put("plan_id", planId)
                        put("week_index", day.weekIndex)
                        put("day_index", day.dayIndex)
                        put("day_label", day.dayLabel)
                        put("day_type", day.dayType.dbValue)
                        put("template_id", day.templateId)
                        put("template_name", day.templateName.takeIf { it.isNotBlank() })
                        put("custom_workout_name", day.customWorkoutName.takeIf { it.isNotBlank() })
                    },
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        // Export only after the commit — a snapshot taken inside the transaction
        // would miss the very rows that triggered it.
        if (autoExport) autoExportDatabaseIfConfigured()
        return planId
    }

    fun deleteWorkoutPlan(planId: Long) {
        val db = writableDb()
        db.beginTransaction()
        try {
            db.delete("workout_plan_days", "plan_id = ?", arrayOf(planId.toString()))
            db.delete("workout_plans", "id = ?", arrayOf(planId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        autoExportDatabaseIfConfigured()
    }

    fun getWorkoutPlanSettings(): WorkoutPlanSettings {
        readableDb().rawQuery(
            """
            SELECT primary_weight_step_kg, secondary_weight_step_kg, accessory_rep_step, accessory_set_step,
                   emphasis_strength, emphasis_hypertrophy, emphasis_power, weekly_run_km_target,
                   hyp_rep_min, hyp_rep_max, strength_sets, strength_reps,
                   power_sets, power_rep_min, power_rep_max, power_percent,
                   deload_after_stalls, layoff_days
            FROM workout_plan_settings
            WHERE id = 1
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return WorkoutPlanSettings()
            return WorkoutPlanSettings(
                primaryWeightStepKg = cursor.doubleOrNull("primary_weight_step_kg") ?: 2.5,
                secondaryWeightStepKg = cursor.doubleOrNull("secondary_weight_step_kg") ?: 2.5,
                accessoryRepStep = cursor.intOrNull("accessory_rep_step") ?: 1,
                accessorySetStep = cursor.intOrNull("accessory_set_step") ?: 0,
                emphasisStrength = cursor.intOrNull("emphasis_strength") ?: 40,
                emphasisHypertrophy = cursor.intOrNull("emphasis_hypertrophy") ?: 40,
                emphasisPower = cursor.intOrNull("emphasis_power") ?: 20,
                weeklyRunKmTarget = cursor.doubleOrNull("weekly_run_km_target"),
                hypRepMin = cursor.intOrNull("hyp_rep_min") ?: 8,
                hypRepMax = cursor.intOrNull("hyp_rep_max") ?: 12,
                strengthSets = cursor.intOrNull("strength_sets") ?: 5,
                strengthReps = cursor.intOrNull("strength_reps") ?: 5,
                powerSets = cursor.intOrNull("power_sets") ?: 3,
                powerRepMin = cursor.intOrNull("power_rep_min") ?: 5,
                powerRepMax = cursor.intOrNull("power_rep_max") ?: 8,
                powerPercent = cursor.intOrNull("power_percent") ?: 50,
                deloadAfterStalls = cursor.intOrNull("deload_after_stalls") ?: 6,
                layoffDays = cursor.intOrNull("layoff_days") ?: 21,
            )
        }
    }

    fun saveWorkoutPlanSettings(settings: WorkoutPlanSettings, autoExport: Boolean = true) {
        writableDb().insertWithOnConflict(
            "workout_plan_settings",
            null,
            ContentValues().apply {
                put("id", 1)
                put("primary_weight_step_kg", settings.primaryWeightStepKg)
                put("secondary_weight_step_kg", settings.secondaryWeightStepKg)
                put("accessory_rep_step", settings.accessoryRepStep)
                put("accessory_set_step", settings.accessorySetStep)
                put("emphasis_strength", settings.emphasisStrength)
                put("emphasis_hypertrophy", settings.emphasisHypertrophy)
                put("emphasis_power", settings.emphasisPower)
                put("weekly_run_km_target", settings.weeklyRunKmTarget)
                put("hyp_rep_min", settings.hypRepMin)
                put("hyp_rep_max", settings.hypRepMax)
                put("strength_sets", settings.strengthSets)
                put("strength_reps", settings.strengthReps)
                put("power_sets", settings.powerSets)
                put("power_rep_min", settings.powerRepMin)
                put("power_rep_max", settings.powerRepMax)
                put("power_percent", settings.powerPercent)
                put("deload_after_stalls", settings.deloadAfterStalls)
                put("layoff_days", settings.layoffDays)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (autoExport) autoExportDatabaseIfConfigured()
    }

    fun getTodayPlanRecommendation(date: String = LocalDate.now().format(DATE_FORMAT)): PlanRecommendation? {
        val activePlanId = readableDb().rawQuery(
            """
            SELECT id
            FROM workout_plans
            WHERE is_active = 1
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.long("id")
        }
        val activePlan = getWorkoutPlans().firstOrNull { it.id == activePlanId && it.isActive } ?: return null
        val target = runCatching { LocalDate.parse(date, DATE_FORMAT) }.getOrNull() ?: return null
        return recommendationFor(activePlan, target, date)
    }

    /**
     * Pure in-memory mapping of a date onto a plan's week grid. Plan days are
     * anchored to real weekdays — dayIndex 0 = Monday — and week counting
     * starts from the Monday of the start week, so a plan started on a Friday
     * still puts its "Mon" workout on Mondays.
     */
    private fun recommendationFor(plan: WorkoutPlan, target: LocalDate, dateText: String): PlanRecommendation? {
        // The date→slot arithmetic lives in ONE place (planSlotFor) so the day
        // sheet's plan editors can never disagree with what this painted.
        val day = planSlotFor(plan, target) ?: return null
        return PlanRecommendation(
            planName = plan.name,
            dayLabel = day.dayLabel.ifBlank { "Week ${day.weekIndex + 1}, Day ${day.dayIndex + 1}" },
            dayType = day.dayType,
            templateId = day.templateId,
            templateName = day.templateName,
            customWorkoutName = day.customWorkoutName,
            date = dateText,
            useEngine = plan.useEngine,
        )
    }

    /**
     * One calendar month in four queries: a light row scan of the month's
     * history (names + categories only — no set details), the draft list, the
     * plans, and the stats roll-up. The previous version ran the full
     * plan-recommendation and workout-group loaders per day cell (~hundreds
     * of queries per month), which made the scrolling calendar crawl.
     *
     * Days outside the month are not emitted — the grid pads its own leading
     * blanks, so consecutive months don't repeat each other's edge days.
     */
    fun loadCalendarMonth(month: YearMonth): CalendarMonthSnapshot {
        val today = LocalDate.now()
        val startText = month.atDay(1).format(DATE_FORMAT)
        val endText = month.atEndOfMonth().format(DATE_FORMAT)

        data class LightRow(val workout: String, val category: String, val exercise: String)
        val rowsByDate = HashMap<String, MutableList<LightRow>>()
        readableDb().rawQuery(
            """
            SELECT Date, COALESCE(Workout, '') AS Workout, COALESCE(Category, '') AS Category,
                   COALESCE(Exercise, '') AS Exercise
            FROM lifts_new
            WHERE Date BETWEEN ? AND ?
            ORDER BY id ASC
            """.trimIndent(),
            arrayOf(startText, endText),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val date = cursor.string("Date")
                if (date.isBlank()) continue
                rowsByDate.getOrPut(date) { mutableListOf() } += LightRow(
                    workout = cursor.string("Workout"),
                    category = cursor.string("Category"),
                    exercise = cursor.string("Exercise"),
                )
            }
        }

        // Drafts as date→name only. The full draft loader computes performance
        // hints per item (a complete history scan each) — far too heavy for a
        // calendar cell that just needs "a draft exists here".
        val draftNameByDate = HashMap<String, String>()
        readableDb().rawQuery(
            "SELECT workout_date, name FROM workout_sessions WHERE status != ?",
            arrayOf(SessionStatus.COMPLETED.dbValue),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                draftNameByDate[cursor.string("workout_date")] = cursor.string("name")
            }
        }
        val activePlan = getWorkoutPlans().firstOrNull { it.isActive }
        // Template markers resolve lazily and cache per month load — an active
        // plan typically references a handful of templates across 31 days.
        val templates by lazy { getTemplates() }
        val templateMarkers = HashMap<Long, List<CalendarMarkerType>>()
        fun markersForTemplate(templateId: Long?): List<CalendarMarkerType> {
            if (templateId == null) return emptyList()
            return templateMarkers.getOrPut(templateId) {
                templates.firstOrNull { it.id == templateId }
                    ?.items?.map(::markerTypeForTemplateItem)?.distinct().orEmpty()
            }
        }

        val days = (1..month.lengthOfMonth()).map { dayOfMonth ->
            val date = month.atDay(dayOfMonth)
            val dateText = date.format(DATE_FORMAT)
            val rows = rowsByDate[dateText].orEmpty()
            val draftName = draftNameByDate[dateText]
            // Rest days are not events — a plan's rest day gets no outline,
            // no marker, nothing. Only actual workouts mark the calendar.
            val recommendation = activePlan?.let { recommendationFor(it, date, dateText) }
                ?.takeIf { it.dayType != PlanDayType.REST }
                ?.takeUnless { date.isBefore(today) && rows.isEmpty() && draftName == null }
            val state = when {
                rows.isNotEmpty() -> CalendarDayState.COMPLETED
                (draftName != null || recommendation != null) && date.isAfter(today) -> CalendarDayState.FUTURE_SCHEDULED
                (draftName != null || recommendation != null) && !date.isBefore(today) -> CalendarDayState.SCHEDULED
                // A past day holding an unsealed draft is not an empty day — at
                // Sunday review it must read "unfinished", not "missed". The
                // calendar has mapped a gold ring for DRAFT since R7; this is
                // its first producer.
                draftName != null -> CalendarDayState.DRAFT
                else -> CalendarDayState.NONE
            }
            val title = rows.firstOrNull { it.workout.isNotBlank() }?.workout
                ?: rows.firstOrNull()?.exercise
                ?: draftName
                ?: recommendation?.templateName?.takeIf { it.isNotBlank() }
                ?: recommendation?.customWorkoutName
                ?: ""
            val markers = when {
                rows.isNotEmpty() -> rows.map { markerTypeForCategory(it.category, it.exercise) }.distinct()
                recommendation != null -> markersForTemplate(recommendation.templateId)
                else -> emptyList()
            }
            CalendarDaySummary(
                date = dateText,
                dayNumber = dayOfMonth,
                inCurrentMonth = true,
                isToday = date == today,
                state = state,
                workoutName = title,
                markers = markers,
                overflowCount = 0,
            )
        }
        return CalendarMonthSnapshot(
            monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            days = days,
            stats = getMonthStats(month),
        )
    }

    fun loadDayWorkoutSnapshot(date: String): DayWorkoutSnapshot {
        val parsedDate = runCatching { LocalDate.parse(date, DATE_FORMAT) }.getOrNull()
        val completed = getWorkoutGroupsForDate(date)
        val drafts = getDraftSessions().filter { it.workoutDate == date }
        val draft = drafts.firstOrNull()
        val recommendation = getTodayPlanRecommendation(date)
            ?.takeIf { shouldShowRecommendation(it, parsedDate, completed, draft) }
        return DayWorkoutSnapshot(
            date = date,
            recommendation = recommendation,
            draftSession = draft,
            // Extra same-day drafts were invisible while the day was today —
            // the sheet at least says they exist now.
            extraDraftCount = (drafts.size - 1).coerceAtLeast(0),
            completedWorkouts = completed,
        )
    }

    /**
     * A recommendation stops showing once its workout is done — a completed day
     * must not keep offering "begin". Two suppressions:
     *  - past empty days are history, not a to-do list;
     *  - a completed workout whose name matches the recommendation means the
     *    plan was fulfilled. An UNRELATED completion (a morning run before a
     *    lifting day) leaves the plan visible.
     */
    private fun shouldShowRecommendation(
        recommendation: PlanRecommendation,
        parsedDate: LocalDate?,
        completed: List<WorkoutGroup>,
        draft: WorkoutSession?,
    ): Boolean {
        if (parsedDate != null && parsedDate.isBefore(LocalDate.now()) && completed.isEmpty() && draft == null) return false
        val title = recommendation.templateName
            .ifBlank { recommendation.customWorkoutName }
            .ifBlank { recommendation.dayLabel }
        if (title.isNotBlank() && completed.any { it.workoutName.equals(title, ignoreCase = true) }) return false
        // Renaming a session mid-workout used to resurrect "tap to begin" — the
        // name comparison missed the sealed record; the template id doesn't.
        if (recommendation.templateId != null) {
            val doneById = readableDb().rawQuery(
                "SELECT 1 FROM workout_sessions WHERE workout_date = ? AND status = ? AND source_template_id = ? LIMIT 1",
                arrayOf(recommendation.date, SessionStatus.COMPLETED.dbValue, recommendation.templateId.toString()),
            ).use { it.moveToFirst() }
            if (doneById) return false
        }
        return true
    }

    fun getDraftSessions(): List<WorkoutSession> {
        val database = readableDb()
        val sessions = mutableListOf<WorkoutSession>()
        database.rawQuery(
            """
            SELECT id, name, workout_date, source_template_id, status, notes,
                   COALESCE(mood, '') AS mood, COALESCE(diet, '') AS diet, body_weight_kg
            FROM workout_sessions
            WHERE status != ?
            ORDER BY updated_at DESC
            """.trimIndent(),
            arrayOf(SessionStatus.COMPLETED.dbValue),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sessions += WorkoutSession(
                    id = cursor.long("id"),
                    name = cursor.string("name"),
                    workoutDate = cursor.string("workout_date"),
                    sourceTemplateId = cursor.longOrNull("source_template_id"),
                    status = SessionStatus.fromDb(cursor.string("status")),
                    notes = cursor.string("notes"),
                    mood = cursor.string("mood"),
                    diet = cursor.string("diet"),
                    bodyWeightKg = cursor.doubleOrNull("body_weight_kg"),
                    // Same unstamped-row filter as loadSession — a scheduled
                    // draft must not advertise sets it never logged.
                    items = loadSessionItems(cursor.long("id")).map { item ->
                        item.copy(loggedSets = item.loggedSets.filter { it.loggedAtMillis != null })
                    },
                )
            }
        }
        return sessions
    }

    fun loadSession(sessionId: Long): WorkoutSession? {
        val database = readableDb()
        database.rawQuery(
            """
            SELECT id, name, workout_date, source_template_id, status, notes,
                   COALESCE(mood, '') AS mood, COALESCE(diet, '') AS diet, body_weight_kg,
                   started_at_ms, finished_at_ms
            FROM workout_sessions
            WHERE id = ?
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = SessionStatus.fromDb(cursor.string("status"))
            var items = loadSessionItems(sessionId)
            if (status == SessionStatus.DRAFT) {
                // Every genuine log path stamps logged_at_ms. Unstamped rows in a
                // draft are plan residue (older builds pre-seeded target sets) —
                // drop them so a scheduled workout never looks already-done.
                items = items.map { item ->
                    item.copy(loggedSets = item.loggedSets.filter { it.loggedAtMillis != null })
                }
            }
            return WorkoutSession(
                id = cursor.long("id"),
                name = cursor.string("name"),
                workoutDate = cursor.string("workout_date"),
                sourceTemplateId = cursor.longOrNull("source_template_id"),
                status = status,
                notes = cursor.string("notes"),
                mood = cursor.string("mood"),
                diet = cursor.string("diet"),
                bodyWeightKg = cursor.doubleOrNull("body_weight_kg"),
                startedAtMillis = cursor.longOrNull("started_at_ms"),
                finishedAtMillis = cursor.longOrNull("finished_at_ms"),
                items = items,
            )
        }
    }

    fun saveTemplate(template: WorkoutTemplate, autoExport: Boolean = true): Long {
        val database = writableDb()
        val now = nowStamp()
        val templateId: Long
        database.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("name", template.name.trim())
                put("discipline", template.discipline.trim())
                put("notes", template.notes.trim())
                put("updated_at", now)
            }
            val existingId = template.id.takeIf { it != 0L } ?: getTemplateIdByName(template.name)
            templateId = if (existingId == null) {
                values.put("created_at", now)
                database.insertOrThrow("workout_templates", null, values)
            } else {
                database.update("workout_templates", values, "id = ?", arrayOf(existingId.toString()))
                database.delete("workout_template_items", "template_id = ?", arrayOf(existingId.toString()))
                existingId
            }

            template.items.sortedBy { it.sortOrder }.forEachIndexed { index, item ->
                database.insertOrThrow(
                    "workout_template_items",
                    null,
                    ContentValues().apply {
                        put("template_id", templateId)
                        put("sort_order", index)
                        put("exercise_name", item.exerciseName.takeIf { it.isNotBlank() })
                        put("combo_id", item.comboId)
                        put("combo_name", item.comboName.takeIf { it.isNotBlank() })
                        put("progression_role", item.progressionRole.dbValue)
                        put("sets_target", item.setsTarget)
                        put("reps_target", item.repsTarget.takeIf { it.isNotBlank() })
                        put("weight_target", item.weightTarget)
                        put("rest_target_seconds", item.restTargetSeconds)
                        put("duration_target_seconds", item.durationTargetSeconds)
                        put("distance_target_km", item.distanceTargetKm)
                        put("pace_target_seconds_per_km", item.paceTargetSecondsPerKm)
                        put("rpe_target", item.rpeTarget)
                        put("notes", item.notes.takeIf { it.isNotBlank() })
                        put("superset_id", item.supersetId.takeIf { it.isNotBlank() })
                    },
                )
            }

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        // Export only after the commit — see saveWorkoutPlan.
        if (autoExport) autoExportDatabaseIfConfigured()
        return templateId
    }

    fun deleteTemplate(templateId: Long) {
        val database = writableDb()
        database.beginTransaction()
        try {
            database.delete("workout_template_items", "template_id = ?", arrayOf(templateId.toString()))
            database.delete("workout_templates", "id = ?", arrayOf(templateId.toString()))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        autoExportDatabaseIfConfigured()
    }

    fun saveSession(session: WorkoutSession, autoExport: Boolean = true): Long {
        val database = writableDb()
        val now = nowStamp()
        val sessionId: Long
        database.beginTransaction()
        try {
            val sessionValues = ContentValues().apply {
                put("name", session.name.ifBlank { "Workout" }.trim())
                put("workout_date", session.workoutDate.ifBlank { LocalDate.now().format(DATE_FORMAT) })
                put("source_template_id", session.sourceTemplateId)
                put("status", session.status.dbValue)
                put("notes", session.notes.trim())
                put("mood", session.mood.trim())
                put("diet", session.diet.trim())
                put("body_weight_kg", session.bodyWeightKg)
                put("started_at_ms", session.startedAtMillis)
                put("finished_at_ms", session.finishedAtMillis)
                put("updated_at", now)
            }
            sessionId = if (session.id == 0L) {
                sessionValues.put("created_at", now)
                database.insertOrThrow("workout_sessions", null, sessionValues)
            } else {
                database.update("workout_sessions", sessionValues, "id = ?", arrayOf(session.id.toString()))
                database.delete("workout_session_sets", "session_item_id IN (SELECT id FROM workout_session_items WHERE session_id = ?)", arrayOf(session.id.toString()))
                database.delete("workout_session_items", "session_id = ?", arrayOf(session.id.toString()))
                session.id
            }

            session.items.sortedBy { it.sortOrder }.forEachIndexed { index, item ->
                val sessionItemId = database.insertOrThrow(
                    "workout_session_items",
                    null,
                    ContentValues().apply {
                        put("session_id", sessionId)
                        put("sort_order", index)
                        put("exercise_name", item.exerciseName.takeIf { it.isNotBlank() })
                        put("combo_id", item.comboId)
                        put("combo_name", item.comboName.takeIf { it.isNotBlank() })
                        put("progression_role", item.progressionRole.dbValue)
                        put("planned_sets", item.plannedSets)
                        put("target_reps", item.targetReps.takeIf { it.isNotBlank() })
                        put("target_weight", item.targetWeight)
                        put("target_rest_seconds", item.targetRestSeconds)
                        put("target_duration_seconds", item.targetDurationSeconds)
                        put("target_distance_km", item.targetDistanceKm)
                        put("target_pace_seconds_per_km", item.targetPaceSecondsPerKm)
                        put("target_rpe", item.targetRpe)
                        put("notes", item.notes.takeIf { it.isNotBlank() })
                        put("superset_id", item.supersetId.takeIf { it.isNotBlank() })
                        put("coach_note", item.coachNote.takeIf { it.isNotBlank() })
                        put("pending_comment", item.pendingComment.takeIf { it.isNotBlank() })
                    },
                )

                item.loggedSets.sortedBy { it.setIndex }.forEachIndexed { setIndex, set ->
                    database.insertOrThrow(
                        "workout_session_sets",
                        null,
                        ContentValues().apply {
                            put("session_item_id", sessionItemId)
                            put("set_index", setIndex + 1)
                            put("weight", set.weight)
                            put("reps_text", set.repsText.takeIf { it.isNotBlank() })
                            put("combo_id", set.comboId)
                            put("combo_name", set.comboName.takeIf { it.isNotBlank() })
                            put("form_id", set.formId.takeIf { it.isNotBlank() })
                            put("form_move_index", set.formMoveIndex)
                            put("rest_seconds", set.restSeconds)
                            put("duration_seconds", set.durationSeconds)
                            put("distance_km", set.distanceKm)
                            put("pace_seconds_per_km", set.paceSecondsPerKm)
                            put("rpe", set.rpe)
                            put("notes", set.notes.takeIf { it.isNotBlank() })
                            put("comment", set.comment.takeIf { it.isNotBlank() })
                            put("logged_at_ms", set.loggedAtMillis)
                            put("created_at", now)
                        },
                    )
                }
            }

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        // Export only after the commit — see saveWorkoutPlan.
        if (autoExport) autoExportDatabaseIfConfigured()
        return sessionId
    }

    fun deleteSession(sessionId: Long, autoExport: Boolean = true) {
        val database = writableDb()
        database.beginTransaction()
        try {
            database.delete(
                "workout_session_sets",
                "session_item_id IN (SELECT id FROM workout_session_items WHERE session_id = ?)",
                arrayOf(sessionId.toString()),
            )
            database.delete("workout_session_items", "session_id = ?", arrayOf(sessionId.toString()))
            database.delete("workout_sessions", "id = ?", arrayOf(sessionId.toString()))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        if (autoExport) autoExportDatabaseIfConfigured()
    }

    fun completeSession(session: WorkoutSession): Long {
        // Idempotency: a session that already sealed must never flush its rows
        // to history a second time (retry after an error, double-tap on the
        // finish seal). COMPLETED means done — just report the id.
        if (session.id != 0L && loadSession(session.id)?.status == SessionStatus.COMPLETED) {
            return session.id
        }
        var finishedSession = session.copy(
            status = SessionStatus.DRAFT,
            finishedAtMillis = session.finishedAtMillis ?: System.currentTimeMillis(),
        )
        // Two same-day seals sharing one name (two unnamed "Custom Workout"s)
        // used to MERGE into a single record — history groups by (date, Workout)
        // — and a long-press delete then erased both. Number the newcomer.
        val trimmedName = finishedSession.name.trim()
        if (trimmedName.isNotBlank()) {
            fun nameTaken(candidate: String): Boolean = readableDb().rawQuery(
                "SELECT 1 FROM lifts_new WHERE Date = ? AND TRIM(COALESCE(Workout, '')) = ? LIMIT 1",
                arrayOf(finishedSession.workoutDate, candidate),
            ).use { it.moveToFirst() }
            if (nameTaken(trimmedName)) {
                var suffix = 2
                while (nameTaken("$trimmedName · $suffix")) suffix++
                finishedSession = finishedSession.copy(name = "$trimmedName · $suffix")
            }
        }
        // A note still waiting for "the set he's on" when the seal lands goes
        // onto the last logged set, marked "after set N" (R20).
        finishedSession = finishedSession.copy(items = finishedSession.items.map { it.settlePendingComment() })
        val savedId = saveSession(finishedSession, autoExport = false)
        val savedSession = loadSession(savedId) ?: return savedId
        val database = writableDb()
        // Highest move reached per form across the session → one checkpoint
        // each, written after the rows so the Martial Arts hall's "last
        // practised to" follows gym sessions too, not just quick logs.
        val formCheckpoints = mutableMapOf<String, Int>()
        database.beginTransaction()
        try {
            savedSession.items.forEach { item ->
                val combo = item.comboId?.let(::getComboById)
                val exercise = item.exerciseName.takeIf { it.isNotBlank() }?.let(::getExerciseByName)
                item.loggedSets.forEach { set ->
                    val setCombo = set.comboId?.let(::getComboById)
                    val setForm = set.formId.takeIf { it.isNotBlank() }?.let(::getMartialArtsFormById)
                    if (setForm != null && set.formMoveIndex != null) {
                        val previous = formCheckpoints[setForm.id] ?: 0
                        if (set.formMoveIndex > previous) formCheckpoints[setForm.id] = set.formMoveIndex
                    }
                    val rowTitle = when {
                        setForm != null -> "${item.title.ifBlank { "Custom" }}: ${setForm.displayShortTitle}"
                        set.comboName.isNotBlank() -> "${item.title.ifBlank { "Custom" }}: ${set.comboName}"
                        else -> item.title.ifBlank { "Custom" }
                    }
                    val discipline = setForm?.discipline?.takeIf { it.isNotBlank() }
                        ?: setCombo?.discipline
                        ?: combo?.discipline
                    database.insertOrThrow(
                        "lifts_new",
                        null,
                        ContentValues().apply {
                            put("Date", savedSession.workoutDate)
                            put("Exercise", rowTitle)
                            when {
                                set.weight != null -> put("Weight", set.weight)
                                else -> putNull("Weight")
                            }
                            when {
                                set.repsText.isNotBlank() -> put("Reps", set.repsText)
                                else -> putNull("Reps")
                            }
                            put("Rest", set.restSeconds)
                            val checkpointNote = if (setForm != null && set.formMoveIndex != null) {
                                "to move ${set.formMoveIndex}/${setForm.moveCount}"
                            } else {
                                ""
                            }
                            val joinedNotes = listOf(item.notes, set.notes, checkpointNote)
                                .filter { it.isNotBlank() }
                                .joinToString(" | ")
                            if (joinedNotes.isBlank()) {
                                putNull("Notes")
                            } else {
                                put("Notes", joinedNotes)
                            }
                            put("Category", resolveLoggedCategory(exercise, set, discipline))
                            put("Muscle", exercise?.defaultMuscle.orEmpty())
                            put("ComboID", set.comboId ?: item.comboId)
                            put("Discipline", discipline)
                            put("Workout", savedSession.name)
                            put("DurationSeconds", set.durationSeconds)
                            put("DistanceKm", set.distanceKm)
                            put("PaceSecondsPerKm", set.paceSecondsPerKm)
                            put("RPE", set.rpe)
                            put("MYWEIGHT", savedSession.bodyWeightKg)
                            put("Mood", savedSession.mood.takeIf { it.isNotBlank() })
                            put("Diet", savedSession.diet.takeIf { it.isNotBlank() })
                            put("Superset_ID", item.supersetId.takeIf { it.isNotBlank() })
                            put("LoggedAt", set.loggedAtMillis)
                            // His own words, apart from the plan's cues in Notes (R20).
                            put("Comment", set.comment.takeIf { it.isNotBlank() })
                        },
                    )
                }
            }
            formCheckpoints.forEach { (formId, moveIndex) ->
                database.insertOrThrow(
                    "martial_arts_form_checkpoints",
                    null,
                    ContentValues().apply {
                        put("date", savedSession.workoutDate)
                        put("form_id", formId)
                        put("move_index", moveIndex)
                    },
                )
            }
            database.update(
                "workout_sessions",
                ContentValues().apply {
                    put("status", SessionStatus.COMPLETED.dbValue)
                    put("updated_at", nowStamp())
                },
                "id = ?",
                arrayOf(savedId.toString()),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        // ⚖ B7: a set that PROVES a lift goal stamps it achieved on the spot —
        // the engine's TEST-day prescription used to repeat forever until the
        // long-press carve was discovered, sessions after the lift included.
        val provableGoals = getTrainingGoals().filter {
            it.kind == GoalKind.LIFT_TARGET && !it.isAchieved && it.targetWeightKg != null
        }
        if (provableGoals.isNotEmpty()) {
            savedSession.items.forEach { item ->
                val goal = provableGoals.firstOrNull { it.exerciseName.equals(item.exerciseName, ignoreCase = true) }
                    ?: return@forEach
                val proven = item.loggedSets.any { set ->
                    val e1rm = epleyE1rm(set.weight, parseLeadingInt(set.repsText))
                    e1rm != null && e1rm >= (goal.targetWeightKg ?: return@any false)
                }
                if (proven) {
                    saveTrainingGoal(goal.copy(achievedAt = savedSession.workoutDate))
                }
            }
        }
        // The dashboard/KPI/hint caches predate this workout — drop them, or the
        // refresh that follows completion re-serves stale numbers.
        invalidateDashboardKpiCache()
        // Export only after the commit — the snapshot must contain this workout.
        autoExportDatabaseIfConfigured()
        return savedId
    }

    fun saveExercise(item: ExerciseCatalogItem, autoExport: Boolean = true) {
        val database = writableDb()
        try {
            if (item.name.isBlank()) error("Exercise name is required")
            val values = ContentValues().apply {
                put("name", item.name.trim())
                put("default_muscle", item.defaultMuscle.trim())
                put("default_category", item.defaultCategory.trim())
                put("is_bodyweight", if (item.isBodyweight) 1 else 0)
                put("log_format", item.logFormat.dbValue)
                put("movement_type", item.movementType.dbValue)
                put("default_role", item.defaultRole.dbValue.takeIf { item.defaultRole != ProgressionRole.NONE })
                put("weight_unit", item.weightUnit.dbValue)
                put("per_side", if (item.perSide) 1 else null)
            }
            val existingId = item.id.takeIf { it != 0L } ?: getExerciseIdByName(item.name)
            // A changed log_format silently re-shapes hints — drop the cache.
            invalidateDashboardKpiCache()
            if (existingId == null) {
                database.insertOrThrow("exercises", null, values)
            } else {
                database.update("exercises", values, "id = ?", arrayOf(existingId.toString()))
            }
            if (autoExport) autoExportDatabaseIfConfigured()
        } catch (error: SQLiteConstraintException) {
            throw IllegalArgumentException("Exercise name must be unique", error)
        }
    }

    fun deleteExercise(name: String) {
        writableDb().delete("exercises", "name = ?", arrayOf(name))
        autoExportDatabaseIfConfigured()
    }

    /** Set the role an exercise takes when added to a workout (Settings → roles). */
    fun setExerciseDefaultRole(name: String, role: ProgressionRole) {
        if (name.isBlank()) return
        writableDb().update(
            "exercises",
            ContentValues().apply { put("default_role", role.dbValue.takeIf { role != ProgressionRole.NONE }) },
            "name = ?",
            arrayOf(name.trim()),
        )
        autoExportDatabaseIfConfigured()
    }

    /**
     * Set the unit this exercise's stack is labelled in (the KG/LB toggle on the
     * session dial). Display only — no stored weight is rewritten, so flipping the
     * unit re-reads the SAME kilograms in the other unit rather than converting
     * history. An exercise logged for months in mislabelled units needs a
     * deliberate data correction, not this switch.
     */
    fun setExerciseWeightUnit(name: String, unit: WeightUnit) {
        if (name.isBlank()) return
        writableDb().update(
            "exercises",
            ContentValues().apply { put("weight_unit", unit.dbValue) },
            "name = ?",
            arrayOf(name.trim()),
        )
        autoExportDatabaseIfConfigured()
    }

    fun saveCombo(combo: ComboItem, autoExport: Boolean = true) {
        val database = writableDb()
        try {
            if (combo.name.isBlank()) error("Combo name is required")
            val values = ContentValues().apply {
                put("name", combo.name.trim())
                put("discipline", combo.discipline.trim())
                put("notes", combo.notes.trim())
                // moves_json is always written, even when empty, so an existing
                // combo can have its sequence cleared by saving with no moves.
                put("moves_json", encodeComboMovesJson(combo.moves))
            }
            val existingId = combo.id.takeIf { it != 0L } ?: getComboIdByName(combo.name)
            if (existingId == null) {
                database.insertOrThrow("combos", null, values)
            } else {
                database.update("combos", values, "id = ?", arrayOf(existingId.toString()))
            }
            if (autoExport) autoExportDatabaseIfConfigured()
        } catch (error: SQLiteConstraintException) {
            throw IllegalArgumentException("Combo name must be unique", error)
        }
    }

    fun deleteCombo(id: Long) {
        writableDb().delete("combos", "id = ?", arrayOf(id.toString()))
        autoExportDatabaseIfConfigured()
    }

    fun exportDatabase(uri: Uri) {
        val database = writableDb()
        // A snapshot taken mid-transaction would silently miss uncommitted rows —
        // fail loudly instead so the bug can't reappear unnoticed.
        check(!database.inTransaction()) { "exportDatabase called inside a transaction — snapshot would be stale" }
        // TRUNCATE moves every committed WAL frame into the main file (and empties
        // the WAL), so copying just the .db captures the full current state.
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                Log.w(TAG, "wal_checkpoint reported busy — export may lag the newest write")
            }
        }
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        context.contentResolver.openOutputStream(uri)?.use { output ->
            databaseFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Unable to open export target")
    }

    /**
     * Replace the on-device database with the contents of [uri], atomically.
     *
     * The previous implementation closed the helper and copied the new bytes directly
     * over the live database file. A failure mid-copy (truncated stream, full disk,
     * permission revoked, killed app) left the user with a corrupted or truncated
     * database and no way back. This implementation:
     *
     *  1. Streams the import into a sibling temp file (`workouts.db.import.tmp`).
     *  2. Verifies the temp file actually opens as a SQLite database and contains the
     *     `lifts_new` table the rest of the app depends on.
     *  3. Snapshots the current live database to `workouts.db.prev.bak` so that any
     *     failure after the swap can still be recovered manually.
     *  4. Performs an atomic rename of the temp file over the live database. SQLite WAL
     *     and SHM sidecars are deleted before reopening so the new DB starts clean.
     *  5. Re-opens the helper and triggers the auto-backup as before.
     *
     * On any failure during stages 1-2 the temp file is cleaned up and the live database
     * is left untouched. On a failure during stages 3-5 the previous-version snapshot
     * is left in place at `workouts.db.prev.bak` next to the database file so the user
     * can recover via Settings → Import.
     */
    fun importDatabase(uri: Uri) {
        val destination = context.getDatabasePath(DATABASE_NAME)
        destination.parentFile?.mkdirs()
        val tempFile = File(destination.parentFile, "$DATABASE_NAME.import.tmp")
        val previousBackup = File(destination.parentFile, "$DATABASE_NAME.prev.bak")

        // Stage 1: stream incoming bytes to a temp file. If anything in this stage fails
        // the live database is untouched.
        tempFile.delete()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open import source")

            // Stage 2: verify the temp file is a valid SQLite DB containing lifts_new.
            // This guards against truncated downloads, wrong-format files, and
            // accidentally pointing the import at a non-DB document.
            verifyImportedDatabase(tempFile)
        } catch (error: Throwable) {
            tempFile.delete()
            Log.e(TAG, "Database import validation failed; live database preserved", error)
            throw error
        }

        // Stage 3+: from here on we are committing the swap. Close the live helper so
        // we can rename over the file, then snapshot the existing DB to .prev.bak for
        // emergency recovery before the rename.
        helper.close()
        try {
            if (destination.exists()) {
                previousBackup.delete()
                if (!destination.renameTo(previousBackup)) {
                    // Fall back to a copy-then-delete if rename across filesystems failed.
                    destination.copyTo(previousBackup, overwrite = true)
                    destination.delete()
                }
            }
            if (!tempFile.renameTo(destination)) {
                // Cross-filesystem rename fallback.
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
            File("${destination.path}-wal").delete()
            File("${destination.path}-shm").delete()
        } catch (error: Throwable) {
            // Best-effort recovery: if we still have the previous backup and the live DB
            // is missing or partial, restore the backup so the app can boot.
            Log.e(TAG, "Database import swap failed; attempting recovery from backup", error)
            runCatching {
                if (previousBackup.exists() && !destination.exists()) {
                    previousBackup.copyTo(destination, overwrite = true)
                }
            }.onFailure { Log.e(TAG, "Recovery copy from previousBackup failed", it) }
            tempFile.delete()
            // Re-open helper so the app can keep running on whatever we recovered.
            reopenHelper()
            throw error
        }

        reopenHelper()
        autoExportDatabaseIfConfigured()
    }

    /**
     * Open [file] as a SQLite database in read-only mode and confirm the schema we rely
     * on is present. Throws if the file is unreadable, not a SQLite database, or missing
     * the `lifts_new` table. Does NOT mutate [file] in any way.
     */
    private fun verifyImportedDatabase(file: File) {
        val probe = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val hasLiftsNew = probe.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'lifts_new' LIMIT 1",
                null,
            ).use { it.moveToFirst() }
            if (!hasLiftsNew) {
                error("Imported file is missing the lifts_new table")
            }
        } finally {
            probe.close()
        }
    }

    fun importTarget(uri: Uri, target: ImportTarget) {
        when (target) {
            ImportTarget.FULL_DATABASE -> importDatabase(uri)
            ImportTarget.LIFTS_NEW_MERGE -> importLiftsNew(uri, replaceExisting = false)
            ImportTarget.LIFTS_NEW_REPLACE -> importLiftsNew(uri, replaceExisting = true)
            ImportTarget.EXERCISES -> importExercises(uri)
            ImportTarget.COMBOS -> importCombos(uri)
            ImportTarget.WORKOUTS -> importWorkouts(uri)
            ImportTarget.WORKOUT_PLANS -> importWorkoutPlans(uri)
        }
        // Whatever came in, the caches describe the world before it.
        invalidateDashboardKpiCache()
    }

    private fun importExercises(uri: Uri) {
        withImportedDatabase(uri) { source ->
            if (!hasTable(source, "exercises")) error("Source database has no exercises table")
            // Save paths write EVERY column, so an import that reads only some
            // must carry the target's existing values for the rest — a partial
            // read here once reset every movement-type/role override to defaults.
            val sourceColumns = getTableColumns(source, "exercises")
            val hasMovement = "movement_type" in sourceColumns
            val hasRole = "default_role" in sourceColumns
            val hasUnit = "weight_unit" in sourceColumns
            val hasPerSide = "per_side" in sourceColumns
            source.rawQuery(
                """
                SELECT name, COALESCE(default_muscle, '') AS default_muscle,
                       COALESCE(default_category, '') AS default_category,
                       COALESCE(is_bodyweight, 0) AS is_bodyweight,
                       COALESCE(log_format, 'reps') AS log_format
                       ${if (hasMovement) ", movement_type" else ""}
                       ${if (hasRole) ", default_role" else ""}
                       ${if (hasUnit) ", weight_unit" else ""}
                       ${if (hasPerSide) ", per_side" else ""}
                FROM exercises
                ORDER BY name
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.string("name")
                    val existing = getExerciseByName(name)
                    val base = existing ?: ExerciseCatalogItem(
                        name = name,
                        defaultMuscle = "",
                        defaultCategory = "",
                        logFormat = LogFormat.REPS,
                        isBodyweight = false,
                    )
                    saveExercise(
                        base.copy(
                            name = name,
                            defaultMuscle = cursor.string("default_muscle"),
                            defaultCategory = cursor.string("default_category"),
                            logFormat = LogFormat.fromDb(cursor.string("log_format")),
                            isBodyweight = cursor.int("is_bodyweight") == 1,
                            movementType = if (hasMovement) MovementType.fromDb(cursor.string("movement_type")) else base.movementType,
                            defaultRole = if (hasRole) ProgressionRole.fromDb(cursor.string("default_role")) else base.defaultRole,
                            weightUnit = if (hasUnit) WeightUnit.fromDb(cursor.string("weight_unit")) else base.weightUnit,
                            perSide = if (hasPerSide) cursor.intOrNull("per_side") == 1 else base.perSide,
                        ),
                        autoExport = false,
                    )
                }
            }
        }
        autoExportDatabaseIfConfigured()
    }

    private fun importCombos(uri: Uri) {
        withImportedDatabase(uri) { source ->
            if (!hasTable(source, "combos")) error("Source database has no combos table")
            // saveCombo always writes moves_json — importing without reading it
            // used to erase every same-named combo's move sequence.
            val hasMoves = "moves_json" in getTableColumns(source, "combos")
            source.rawQuery(
                """
                SELECT name, COALESCE(discipline, '') AS discipline, COALESCE(notes, '') AS notes
                       ${if (hasMoves) ", COALESCE(moves_json, '') AS moves_json" else ""}
                FROM combos
                ORDER BY name
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.string("name")
                    val moves = if (hasMoves) {
                        parseComboMovesJson(cursor.string("moves_json"))
                    } else {
                        getComboIdByName(name)?.let(::getComboById)?.moves ?: emptyList()
                    }
                    saveCombo(
                        ComboItem(
                            name = name,
                            discipline = cursor.string("discipline"),
                            notes = cursor.string("notes"),
                            moves = moves,
                        ),
                        autoExport = false,
                    )
                }
            }
        }
        autoExportDatabaseIfConfigured()
    }

    private fun importWorkouts(uri: Uri) {
        withImportedDatabase(uri) { source ->
            if (!hasTable(source, "workout_templates")) error("Source database has no workouts table")
            getTemplates(source).forEach { template ->
                saveTemplate(template.copy(id = 0L), autoExport = false)
            }
        }
        autoExportDatabaseIfConfigured()
    }

    private fun importWorkoutPlans(uri: Uri) {
        withImportedDatabase(uri) { source ->
            if (!hasTable(source, "workout_plans")) error("Source database has no workout plans table")
            getWorkoutPlans(source).forEach { plan ->
                saveWorkoutPlan(
                    plan.copy(
                        id = 0L,
                        days = plan.days.map { day ->
                            val resolvedTemplateId = day.templateName
                                .trim()
                                .takeIf { it.isNotBlank() }
                                ?.let(::getTemplateIdByName)
                            day.copy(id = 0L, templateId = resolvedTemplateId)
                        },
                    ),
                    autoExport = false,
                )
            }
            if (hasTable(source, "workout_plan_settings")) {
                // saveWorkoutPlanSettings REPLACEs the whole row — start from the
                // current settings and override only what the source carries, or
                // an old 4-column import resets every engine tunable to defaults.
                val cols = getTableColumns(source, "workout_plan_settings")
                source.rawQuery("SELECT * FROM workout_plan_settings WHERE id = 1", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val cur = getWorkoutPlanSettings()
                        fun dbl(c: String, fallback: Double) = if (c in cols) cursor.doubleOrNull(c) ?: fallback else fallback
                        fun int(c: String, fallback: Int) = if (c in cols) cursor.intOrNull(c) ?: fallback else fallback
                        saveWorkoutPlanSettings(
                            cur.copy(
                                primaryWeightStepKg = dbl("primary_weight_step_kg", cur.primaryWeightStepKg),
                                secondaryWeightStepKg = dbl("secondary_weight_step_kg", cur.secondaryWeightStepKg),
                                accessoryRepStep = int("accessory_rep_step", cur.accessoryRepStep),
                                accessorySetStep = int("accessory_set_step", cur.accessorySetStep),
                                emphasisStrength = int("emphasis_strength", cur.emphasisStrength),
                                emphasisHypertrophy = int("emphasis_hypertrophy", cur.emphasisHypertrophy),
                                emphasisPower = int("emphasis_power", cur.emphasisPower),
                                weeklyRunKmTarget = if ("weekly_run_km_target" in cols) cursor.doubleOrNull("weekly_run_km_target") else cur.weeklyRunKmTarget,
                                hypRepMin = int("hyp_rep_min", cur.hypRepMin),
                                hypRepMax = int("hyp_rep_max", cur.hypRepMax),
                                strengthSets = int("strength_sets", cur.strengthSets),
                                strengthReps = int("strength_reps", cur.strengthReps),
                                powerSets = int("power_sets", cur.powerSets),
                                powerRepMin = int("power_rep_min", cur.powerRepMin),
                                powerRepMax = int("power_rep_max", cur.powerRepMax),
                                powerPercent = int("power_percent", cur.powerPercent),
                                deloadAfterStalls = int("deload_after_stalls", cur.deloadAfterStalls),
                                layoffDays = int("layoff_days", cur.layoffDays),
                            ),
                            autoExport = false,
                        )
                    }
                }
            }
        }
        autoExportDatabaseIfConfigured()
    }

    private fun importLiftsNew(uri: Uri, replaceExisting: Boolean) {
        withImportedDatabase(uri) { source ->
            if (!hasTable(source, "lifts_new")) error("Source database has no lifts_new table")
            val targetDatabase = writableDb()
            val sourceColumns = getTableColumns(source, "lifts_new")
            val targetColumns = getTableColumns(targetDatabase, "lifts_new")
            val columns = targetColumns.filter { it != "id" && it in sourceColumns }
            if (columns.isEmpty()) error("No shared lifts_new columns found")
            // Multiset dedupe: identical rows are NORMAL in lifting history
            // (3×8 @ 80 kg with NULL LoggedAt is three byte-identical rows), so
            // dedupe by COUNT per signature — the target ends with
            // max(target copies, source copies) of each row, never fewer.
            val remainingSignatures = if (replaceExisting) {
                mutableMapOf<String, Int>()
            } else {
                loadExistingRowSignatureCounts(targetDatabase, "lifts_new", columns)
            }
            targetDatabase.beginTransaction()
            try {
                if (replaceExisting) {
                    targetDatabase.delete("lifts_new", null, null)
                }
                source.query("lifts_new", columns.toTypedArray(), null, null, null, null, "id").use { cursor ->
                    while (cursor.moveToNext()) {
                        val signature = rowSignature(cursor, columns)
                        val already = remainingSignatures[signature] ?: 0
                        if (!replaceExisting && already > 0) {
                            remainingSignatures[signature] = already - 1
                            continue
                        }
                        targetDatabase.insertOrThrow("lifts_new", null, cursorRowToValues(cursor, columns))
                    }
                }
                targetDatabase.setTransactionSuccessful()
            } finally {
                targetDatabase.endTransaction()
            }
        }
        autoExportDatabaseIfConfigured()
    }

    private fun withImportedDatabase(uri: Uri, block: (SQLiteDatabase) -> Unit) {
        val tempFile = File.createTempFile("import-", ".db", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open import source")
            SQLiteDatabase.openDatabase(tempFile.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
        } finally {
            File("${tempFile.path}-wal").delete()
            File("${tempFile.path}-shm").delete()
            tempFile.delete()
        }
    }

    private fun hasTable(database: SQLiteDatabase, tableName: String): Boolean {
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun getTableColumns(database: SQLiteDatabase, tableName: String): List<String> {
        val columns = mutableListOf<String>()
        database.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            while (cursor.moveToNext()) {
                columns += cursor.string("name")
            }
        }
        return columns
    }

    /** Per-signature row COUNTS (not a set — identical history rows are real data). */
    private fun loadExistingRowSignatureCounts(database: SQLiteDatabase, tableName: String, columns: List<String>): MutableMap<String, Int> {
        val signatures = mutableMapOf<String, Int>()
        database.query(tableName, columns.toTypedArray(), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val signature = rowSignature(cursor, columns)
                signatures[signature] = (signatures[signature] ?: 0) + 1
            }
        }
        return signatures
    }

    private fun rowSignature(cursor: Cursor, columns: List<String>): String =
        columns.joinToString("\u001F") { column ->
            val index = cursor.getColumnIndexOrThrow(column)
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> ""
                Cursor.FIELD_TYPE_INTEGER -> "i:${cursor.getLong(index)}"
                Cursor.FIELD_TYPE_FLOAT -> "f:${cursor.getDouble(index)}"
                Cursor.FIELD_TYPE_STRING -> "s:${cursor.getString(index)}"
                Cursor.FIELD_TYPE_BLOB -> "b:${cursor.getBlob(index).contentHashCode()}"
                else -> cursor.getString(index).orEmpty()
            }
        }

    private fun cursorRowToValues(cursor: Cursor, columns: List<String>): ContentValues {
        val values = ContentValues()
        columns.forEach { column ->
            val index = cursor.getColumnIndexOrThrow(column)
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> values.put(column, cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> values.put(column, cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> values.put(column, cursor.getBlob(index))
            }
        }
        return values
    }

    /**
     * Doubles as the "post-mutation hook": every DB mutation in this repository ends
     * by calling this method, so we use it as the single chokepoint to invalidate
     * the dashboard KPI cache and write a backup snapshot to the configured SAF
     * folder if the user has set one up.
     *
     * Snapshots are timestamped (`workouts.db.YYYYMMDD-HHMMSS.bak`) so the user keeps
     * a rolling history rather than overwriting a single file — that way a corrupt
     * save can be recovered from the previous snapshot. Once more than
     * [MAX_AUTO_BACKUPS] snapshots exist, the oldest are pruned.
     */
    fun autoExportDatabaseIfConfigured() {
        // Reaching this point implies either (a) a DB mutation just happened, in
        // which case dropping the cache is correct, or (b) the user is configuring
        // auto-backup with no DB change, in which case dropping a cache that was
        // about to be recomputed anyway costs us nothing measurable.
        invalidateDashboardKpiCache()
        val treeUri = getAutoBackupTreeUri() ?: return
        // A backup must never break the save that triggered it. Failures are
        // recorded (shown in Settings) instead of thrown into the mutation path —
        // an unmounted synced folder should cost a backup, not a workout.
        runCatching { writeAutoBackupSnapshot(treeUri) }
            .onSuccess { snapshotName -> recordBackupResult(ok = true, detail = snapshotName) }
            .onFailure { error ->
                Log.e(TAG, "Auto-backup failed", error)
                recordBackupResult(ok = false, detail = error.message ?: "write failed")
            }
    }

    /** Write one timestamped snapshot + prune old ones. Returns the snapshot name. */
    private fun writeAutoBackupSnapshot(treeUri: Uri): String {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childDocumentsUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

        // Step 1: collect all existing snapshot files in the folder. We track the
        // legacy "workouts.db" filename (older app versions wrote there) plus any
        // "workouts.db.<timestamp>.bak" rolling backups we wrote ourselves.
        data class BackupEntry(val docId: String, val name: String)
        val existing = mutableListOf<BackupEntry>()
        context.contentResolver.query(
            childDocumentsUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (name == DATABASE_NAME || (name.startsWith("$DATABASE_NAME.") && name.endsWith(".bak"))) {
                    existing += BackupEntry(docId, name)
                }
            }
        }

        // Step 2: write the new snapshot. The filename embeds a sortable timestamp
        // so the directory listing reads chronologically.
        val stamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMAT)
        val snapshotName = "$DATABASE_NAME.$stamp.bak"
        val targetUri = DocumentsContract.createDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId),
            "application/octet-stream",
            snapshotName,
        ) ?: error("Backup folder unavailable — re-choose it in Settings")
        exportDatabase(targetUri)

        // Step 3: prune. Keep the [MAX_AUTO_BACKUPS] most recent .bak files plus the
        // legacy single-file "workouts.db" entry (if any) — deleting the legacy
        // file would surprise users who manually relied on it. Sort by name (which
        // is stamp-sorted thanks to ISO-style YYYYMMDD-HHMMSS).
        val timestamped = existing.filter { it.name != DATABASE_NAME }.sortedBy { it.name }
        val toDelete = if (timestamped.size > MAX_AUTO_BACKUPS) {
            timestamped.subList(0, timestamped.size - MAX_AUTO_BACKUPS)
        } else {
            emptyList()
        }
        for (entry in toDelete) {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId)
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, documentUri) }
                .onFailure { Log.w(TAG, "Failed to prune old auto-backup ${entry.name}", it) }
        }
        return snapshotName
    }

    fun getLastBackupStatus(): BackupStatus? {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(LAST_BACKUP_TIME_KEY, 0L)
        if (time == 0L) return null
        return BackupStatus(
            timeMillis = time,
            ok = prefs.getBoolean(LAST_BACKUP_OK_KEY, false),
            detail = prefs.getString(LAST_BACKUP_DETAIL_KEY, "").orEmpty(),
        )
    }

    private fun recordBackupResult(ok: Boolean, detail: String) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_BACKUP_TIME_KEY, System.currentTimeMillis())
            .putBoolean(LAST_BACKUP_OK_KEY, ok)
            .putString(LAST_BACKUP_DETAIL_KEY, detail)
            .apply()
    }

    // region Folder ingest
    //
    // The declarative layer: a folder on the phone (typically kept in sync with a computer)
    // holds JSON definitions — exercises, combos, martial-arts forms, workout
    // templates, and schedule entries — written by hand or by tools elsewhere.
    // The app INGESTS them (upsert by name/id, idempotent); it never writes back
    // except for a single ingest-report.json. Logs flow the other way (auto-backup
    // snapshots). One writer per direction — that asymmetry is the design.

    fun setIngestFolder(uri: Uri?) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit().apply {
            if (uri == null) remove(INGEST_TREE_URI_KEY) else putString(INGEST_TREE_URI_KEY, uri.toString())
        }.apply()
    }

    private fun getIngestTreeUri(): Uri? =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getString(INGEST_TREE_URI_KEY, null)?.let(Uri::parse)

    fun getIngestFolderLabel(): String {
        val treeUri = getIngestTreeUri() ?: return ""
        // Same degrade-don't-throw contract as getAutoBackupFolderLabel.
        return runCatching {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: "Configured" else "Configured"
            } ?: "Configured"
        }.getOrDefault("Folder unavailable — re-pick it")
    }

    fun getLastIngestStatus(): BackupStatus? {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(LAST_INGEST_TIME_KEY, 0L)
        if (time == 0L) return null
        return BackupStatus(
            timeMillis = time,
            ok = prefs.getBoolean(LAST_INGEST_OK_KEY, false),
            detail = prefs.getString(LAST_INGEST_DETAIL_KEY, "") ?: "",
        )
    }

    private fun recordIngestResult(ok: Boolean, detail: String) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_INGEST_TIME_KEY, System.currentTimeMillis())
            .putBoolean(LAST_INGEST_OK_KEY, ok)
            .putString(LAST_INGEST_DETAIL_KEY, detail)
            .apply()
    }

    /**
     * Run one ingest pass over the configured ingest folder. Cheap when nothing
     * changed: each file's SHA-256 is remembered and unchanged files are skipped
     * (pass [force] to re-apply regardless — upserts make that harmless).
     * Never throws: per-item failures are collected into the result's errors so
     * one malformed entry can't block the rest.
     */
    fun runFolderIngest(force: Boolean = false): IngestResult {
        val treeUri = getIngestTreeUri() ?: return IngestResult(detail = "No folder configured")
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val errors = mutableListOf<String>()
        val counts = linkedMapOf<String, Int>()
        fun bump(kind: String) { counts[kind] = (counts[kind] ?: 0) + 1 }

        val children = runCatching { listIngestChildren(treeUri) }.getOrElse { error ->
            val detail = "Folder unreadable: ${error.message ?: "unknown"}"
            recordIngestResult(ok = false, detail = detail)
            return IngestResult(detail = detail, errors = listOf(detail))
        }

        // Read every known file up front, hash-gate each independently.
        val known = listOf("exercises.json", "combos.json", "forms.json", "workouts.json", "schedule.json")
        val payloads = mutableMapOf<String, String>()
        val newHashes = mutableMapOf<String, String>()
        for (name in known) {
            val docId = children[name] ?: continue
            val text = runCatching { readIngestChildText(treeUri, docId) }
                .onFailure { errors += "$name: read failed (${it.message})" }
                .getOrNull() ?: continue
            val hash = sha256Hex(text)
            newHashes[name] = hash
            val priorHash = prefs.getString(INGEST_HASH_KEY_PREFIX + name, null)
            if (force || priorHash != hash) payloads[name] = text
        }
        if (payloads.isEmpty() && errors.isEmpty()) {
            // Record the check too — "current and quiet" must be tellable apart
            // from "ingest stopped running days ago" in Settings.
            recordIngestResult(ok = true, detail = "Up to date")
            return IngestResult(detail = "Up to date", upToDate = true)
        }

        // Apply in dependency order: disciplines/forms and exercises/combos first,
        // then the templates that reference them, then the schedule that references
        // the templates.
        val failedFiles = mutableSetOf<String>()
        fun applyFile(name: String, apply: (String) -> Unit) {
            payloads[name]?.let { text ->
                runCatching { apply(text) }.onFailure {
                    errors += "$name: ${it.message}"
                    failedFiles += name
                }
            }
        }
        applyFile("forms.json") { applyFormsJson(it, ::bump, errors) }
        applyFile("exercises.json") { applyExercisesJson(it, ::bump, errors) }
        applyFile("combos.json") { applyCombosJson(it, ::bump, errors) }
        applyFile("workouts.json") { applyWorkoutsJson(it, ::bump, errors) }
        applyFile("schedule.json") { applyScheduleJson(it, ::bump, errors) }

        // Remember hashes only for files whose apply pass ran without a file-level
        // throw — a failed file stays "dirty" and is retried next launch. (The
        // unconditional write here once marked failed files clean forever.)
        val edit = prefs.edit()
        payloads.keys.forEach { name ->
            if (name in failedFiles) return@forEach
            newHashes[name]?.let { edit.putString(INGEST_HASH_KEY_PREFIX + name, it) }
        }
        edit.apply()

        val applied = counts.values.sum()
        val detail = when {
            applied == 0 && errors.isEmpty() -> "Nothing to apply"
            errors.isEmpty() -> counts.entries.joinToString(", ") { "${it.value} ${it.key}" }
            else -> counts.entries.joinToString(", ") { "${it.value} ${it.key}" } + " · ${errors.size} error(s)"
        }
        recordIngestResult(ok = errors.isEmpty(), detail = detail)
        runCatching { writeIngestReport(treeUri, children, counts, errors) }
            .onFailure { Log.w(TAG, "Ingest report write failed", it) }
        if (applied > 0) {
            invalidateDashboardKpiCache()
            // Definitions changed the DB → send a snapshot down the log river.
            autoExportDatabaseIfConfigured()
        }
        return IngestResult(applied = applied, detail = detail, errors = errors)
    }

    private fun applyExercisesJson(text: String, bump: (String) -> Unit, errors: MutableList<String>) {
        val array = JSONObject(text).optJSONArray("exercises") ?: return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) { errors += "exercises[$i]: missing name"; continue }
            runCatching {
                // The stack unit is set at the machine, on the phone — the ingest folder
                // usually has no opinion about it. So keep whatever the phone
                // already knows unless the file says otherwise; without this,
                // every ingest would silently reset the gym's pound machines
                // back to kilos.
                val existing = getExerciseByName(name)
                val declaredUnit = obj.optString("weightUnit").trim().lowercase(Locale.ROOT)
                val unit = if (declaredUnit.isEmpty()) {
                    existing?.weightUnit ?: WeightUnit.KG
                } else {
                    WeightUnit.fromDb(declaredUnit)
                }
                // Sidedness is a definition, so the ingest folder owns it (R20) — but an
                // exercises.json written before the key existed must not quietly
                // clear it. Present = the file's word; absent = keep what's known.
                val perSide = if (obj.has("perSide")) obj.optBoolean("perSide", false) else existing?.perSide ?: false
                saveExercise(
                    ExerciseCatalogItem(
                        name = name,
                        defaultMuscle = obj.optString("muscle").trim(),
                        defaultCategory = obj.optString("category").trim(),
                        logFormat = LogFormat.fromDb(obj.optString("logFormat", "reps").trim().lowercase(Locale.ROOT)),
                        isBodyweight = obj.optBoolean("bodyweight", false),
                        movementType = MovementType.fromDb(
                            obj.optString("movementType").trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && it != "auto" },
                        ),
                        defaultRole = ProgressionRole.fromDb(obj.optString("role").trim().lowercase(Locale.ROOT)),
                        weightUnit = unit,
                        perSide = perSide,
                    ),
                    autoExport = false,
                )
                bump("exercises")
            }.onFailure { errors += "exercise '$name': ${it.message}" }
        }
    }

    private fun applyCombosJson(text: String, bump: (String) -> Unit, errors: MutableList<String>) {
        val array = JSONObject(text).optJSONArray("combos") ?: return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) { errors += "combos[$i]: missing name"; continue }
            val moveIds = obj.optJSONArray("moves")
            val moves = mutableListOf<ComboMove>()
            if (moveIds != null) {
                for (m in 0 until moveIds.length()) {
                    val id = moveIds.optString(m).trim()
                    val move = ComboMove.fromId(id)
                    if (move == null) errors += "combo '$name': unknown move id '$id'" else moves += move
                }
            }
            runCatching {
                saveCombo(
                    ComboItem(
                        name = name,
                        discipline = obj.optString("discipline").trim(),
                        notes = obj.optString("notes").trim(),
                        moves = moves,
                    ),
                    autoExport = false,
                )
                bump("combos")
            }.onFailure { errors += "combo '$name': ${it.message}" }
        }
    }

    private fun applyFormsJson(text: String, bump: (String) -> Unit, errors: MutableList<String>) {
        val root = JSONObject(text)
        root.optJSONArray("disciplines")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) { errors += "disciplines[$i]: missing name"; continue }
                runCatching {
                    saveMartialArtsDiscipline(
                        MartialArtsDiscipline(
                            name = name,
                            glyph = obj.optString("glyph").trim(),
                            tagline = obj.optString("tagline").trim(),
                        ),
                    )
                    bump("disciplines")
                }.onFailure { errors += "discipline '$name': ${it.message}" }
            }
        }
        root.optJSONArray("forms")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                if (title.isEmpty()) { errors += "forms[$i]: missing title"; continue }
                // A form without an id used to grow a fresh slug per apply
                // (my_form, my_form_2, …) — derive the deterministic slug from
                // the title instead so re-applies upsert the same row.
                val id = obj.optString("id").trim().ifBlank { formSlugBase(title) }
                runCatching {
                    val moves = mutableListOf<MartialArtsMove>()
                    obj.optJSONArray("moves")?.let { movesArray ->
                        for (m in 0 until movesArray.length()) {
                            val moveObj = movesArray.optJSONObject(m) ?: continue
                            moves += MartialArtsMove(
                                index = moveObj.optInt("index", m + 1),
                                name = moveObj.optString("name").trim(),
                                section = moveObj.optString("section").trim(),
                            )
                        }
                    }
                    // saveMartialArtsForm writes mastered_at verbatim — carry an
                    // existing mastery stamp forward so ingest can't strip it.
                    val existingMastery = getMartialArtsFormById(id)?.masteredAt.orEmpty()
                    saveMartialArtsForm(
                        MartialArtsForm(
                            id = id,
                            title = title,
                            subtitle = obj.optString("subtitle").trim(),
                            discipline = obj.optString("discipline", "Tai Chi").trim(),
                            shortTitle = obj.optString("shortTitle").trim(),
                            masteredAt = existingMastery,
                            moves = moves,
                        ),
                    )
                    bump("forms")
                }.onFailure { errors += "form '$title': ${it.message}" }
            }
        }
    }

    private fun applyWorkoutsJson(text: String, bump: (String) -> Unit, errors: MutableList<String>) {
        val array = JSONObject(text).optJSONArray("workouts") ?: return
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) { errors += "workouts[$i]: missing name"; continue }
            val itemsArray = obj.optJSONArray("items") ?: JSONArray()
            val items = mutableListOf<WorkoutTemplateItem>()
            for (j in 0 until itemsArray.length()) {
                val itemObj = itemsArray.optJSONObject(j) ?: continue
                val exerciseName = itemObj.optString("exercise").trim()
                val comboName = itemObj.optString("combo").trim()
                if (exerciseName.isEmpty() && comboName.isEmpty()) {
                    errors += "workout '$name' item[$j]: needs exercise or combo"
                    continue
                }
                val comboId = comboName.takeIf { it.isNotEmpty() }?.let(::getComboIdByName)
                if (comboName.isNotEmpty() && comboId == null) {
                    errors += "workout '$name': unknown combo '$comboName'"
                }
                items += WorkoutTemplateItem(
                    sortOrder = j,
                    exerciseName = exerciseName,
                    comboId = comboId,
                    comboName = comboName,
                    progressionRole = ProgressionRole.fromDb(itemObj.optString("role").trim().lowercase(Locale.ROOT)),
                    setsTarget = itemObj.optIntOrNull("sets"),
                    repsTarget = itemObj.optString("reps").trim(),
                    weightTarget = itemObj.optDoubleOrNull("weight"),
                    restTargetSeconds = itemObj.optIntOrNull("restSeconds"),
                    durationTargetSeconds = itemObj.optIntOrNull("durationSeconds"),
                    distanceTargetKm = itemObj.optDoubleOrNull("distanceKm"),
                    paceTargetSecondsPerKm = itemObj.optIntOrNull("paceSecondsPerKm"),
                    rpeTarget = itemObj.optIntOrNull("rpe"),
                    notes = itemObj.optString("notes").trim(),
                    supersetId = itemObj.optString("supersetId").trim(),
                )
            }
            runCatching {
                saveTemplate(
                    WorkoutTemplate(
                        name = name,
                        discipline = obj.optString("discipline").trim(),
                        notes = obj.optString("notes").trim(),
                        items = items,
                    ),
                    autoExport = false,
                )
                bump("workouts")
            }.onFailure { errors += "workout '$name': ${it.message}" }
        }
    }

    private fun applyScheduleJson(text: String, bump: (String) -> Unit, errors: MutableList<String>) {
        val root = JSONObject(text)
        val dayNames = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        root.optJSONArray("plans")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) { errors += "plans[$i]: missing name"; continue }
                // A plan whose startDate doesn't parse would save fine and then
                // paint nothing forever — reject it loudly instead.
                val startDate = obj.optString("startDate").trim()
                if (runCatching { LocalDate.parse(startDate) }.getOrNull() == null) {
                    errors += "plan '$name': bad or missing startDate '$startDate'"
                    continue
                }
                val weeks = obj.optInt("weeks", 1).coerceAtLeast(1)
                val days = mutableListOf<WorkoutPlanDay>()
                val daysArray = obj.optJSONArray("days") ?: JSONArray()
                for (d in 0 until daysArray.length()) {
                    val dayObj = daysArray.optJSONObject(d) ?: continue
                    val dayIndex = dayNames.indexOf(dayObj.optString("day").trim().lowercase(Locale.ROOT))
                    if (dayIndex < 0) { errors += "plan '$name' days[$d]: bad day"; continue }
                    val weekIndex = (dayObj.optInt("week", 1) - 1).coerceAtLeast(0)
                    if (weekIndex >= weeks) {
                        // A slot beyond the plan's week count is unreachable by
                        // any date — the kind of silent no-op the errors exist for.
                        errors += "plan '$name' days[$d]: week ${weekIndex + 1} of a $weeks-week plan"
                        continue
                    }
                    val templateName = dayObj.optString("workout").trim()
                    val customName = dayObj.optString("custom").trim()
                    val dayType = when {
                        templateName.isNotEmpty() -> PlanDayType.TEMPLATE
                        customName.isNotEmpty() -> PlanDayType.CUSTOM
                        else -> PlanDayType.REST
                    }
                    if (dayType == PlanDayType.TEMPLATE && getTemplateIdByName(templateName) == null) {
                        errors += "plan '$name': unknown workout '$templateName'"
                    }
                    days += WorkoutPlanDay(
                        weekIndex = weekIndex,
                        dayIndex = dayIndex,
                        dayLabel = dayObj.optString("label").trim(),
                        dayType = dayType,
                        templateId = templateName.takeIf { it.isNotEmpty() }?.let(::getTemplateIdByName),
                        templateName = templateName,
                        customWorkoutName = customName,
                    )
                }
                runCatching {
                    saveWorkoutPlan(
                        WorkoutPlan(
                            name = name,
                            startDate = startDate,
                            weeks = weeks,
                            isActive = obj.optBoolean("active", false),
                            repeatWeekly = obj.optBoolean("repeatWeekly", false),
                            useEngine = obj.optBoolean("useEngine", true),
                            days = days,
                        ),
                        autoExport = false,
                    )
                    bump("plans")
                }.onFailure { errors += "plan '$name': ${it.message}" }
            }
        }
        root.optJSONArray("sessions")?.let { array ->
            val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            val ledger = (prefs.getString(INGEST_SESSION_LEDGER_KEY, "") ?: "")
                .split("\n").filter { it.isNotBlank() }.toMutableSet()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val date = obj.optString("date").trim()
                val workoutName = obj.optString("workout").trim()
                if (date.isEmpty() || workoutName.isEmpty()) {
                    errors += "sessions[$i]: needs date + workout"
                    continue
                }
                val key = obj.optString("id").trim().ifEmpty { "$date·$workoutName" }
                if (key in ledger) continue
                // Belt and braces beyond the ledger: if the day already carries this
                // workout (drafted or done), don't create a duplicate.
                val snapshot = loadDayWorkoutSnapshot(date)
                val alreadyThere = snapshot.draftSession?.name.equals(workoutName, ignoreCase = true) ||
                    snapshot.completedWorkouts.any { it.workoutName.equals(workoutName, ignoreCase = true) }
                if (alreadyThere) { ledger += key; continue }
                val template = getTemplates().firstOrNull { it.name.equals(workoutName, ignoreCase = true) }
                if (template == null) {
                    errors += "session '$key': unknown workout '$workoutName'"
                    continue
                }
                runCatching {
                    saveSession(buildSessionFromTemplate(template, date), autoExport = false)
                    ledger += key
                    bump("sessions")
                }.onFailure { errors += "session '$key': ${it.message}" }
            }
            prefs.edit().putString(INGEST_SESSION_LEDGER_KEY, ledger.joinToString("\n")).apply()
        }
    }

    /** Child display-name → documentId for the ingest folder's direct children. */
    private fun listIngestChildren(treeUri: Uri): Map<String, String> {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childDocumentsUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val children = mutableMapOf<String, String>()
        context.contentResolver.query(
            childDocumentsUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val docId = cursor.getString(1) ?: continue
                children[name] = docId
            }
        }
        return children
    }

    private fun readIngestChildText(treeUri: Uri, documentId: String): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return context.contentResolver.openInputStream(documentUri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: error("Could not open document")
    }

    private fun writeIngestReport(
        treeUri: Uri,
        children: Map<String, String>,
        counts: Map<String, Int>,
        errors: List<String>,
    ) {
        val report = JSONObject().apply {
            put("at", LocalDateTime.now().format(TIMESTAMP_FORMAT))
            put("ok", errors.isEmpty())
            put("applied", JSONObject().apply { counts.forEach { (kind, count) -> put(kind, count) } })
            put("errors", JSONArray(errors))
        }
        val reportName = "ingest-report.json"
        val existingDocId = children[reportName]
        val targetUri = if (existingDocId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
        } else {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId),
                "application/json",
                reportName,
            ) ?: error("Could not create $reportName")
        }
        context.contentResolver.openOutputStream(targetUri, "wt")?.use { stream ->
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("Could not open $reportName for writing")
    }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // org.json's optInt/optDouble return 0 for absent keys — these preserve null
    // so an omitted target stays an omitted target instead of becoming zero.
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    // endregion

    fun buildTemplateItemsFromWorkout(workout: WorkoutGroup): List<WorkoutTemplateItem> {
        // Combo rounds are logged one row per round with titles like
        // "Heavy Bag: 1-2, slip right, 2", so a single bag session loads as
        // many one-set entries. Merge consecutive combo entries back into one
        // item per parent exercise ("Heavy Bag") with the round count as the
        // sets target — otherwise replaying a recent workout shows each combo
        // as a fake weights exercise and the round timer never appears.
        data class MergedEntry(
            val exerciseName: String,
            val comboId: Long?,
            val comboName: String,
            val category: String,
            val isCombo: Boolean,
            val sets: MutableList<WorkoutSetRecord>,
        )

        val merged = mutableListOf<MergedEntry>()
        workout.entries.forEach { entry ->
            val isComboEntry = entry.comboId != null ||
                (entry.title.contains(": ") && entry.category.equals("Martial Arts", true))
            if (isComboEntry) {
                val parent = entry.title.substringBefore(":").trim().ifBlank { entry.title }
                val comboName = entry.title.substringAfter(": ", "").trim()
                val previous = merged.lastOrNull()
                if (previous != null && previous.isCombo && previous.exerciseName.equals(parent, ignoreCase = true)) {
                    previous.sets += entry.sets
                } else {
                    merged += MergedEntry(
                        exerciseName = parent,
                        comboId = entry.comboId,
                        comboName = comboName,
                        category = entry.category,
                        isCombo = true,
                        sets = entry.sets.toMutableList(),
                    )
                }
            } else {
                merged += MergedEntry(
                    exerciseName = entry.title,
                    comboId = null,
                    comboName = "",
                    category = entry.category,
                    isCombo = false,
                    sets = entry.sets.toMutableList(),
                )
            }
        }

        return merged.mapIndexed { index, entry ->
            val firstSet = entry.sets.firstOrNull()
            WorkoutTemplateItem(
                id = -(index + 1L),
                sortOrder = index,
                exerciseName = entry.exerciseName,
                comboId = entry.comboId,
                comboName = entry.comboName,
                progressionRole = if (entry.category.equals("Running", true)) ProgressionRole.RUNNING else ProgressionRole.ACCESSORY,
                setsTarget = entry.sets.size.takeIf { it > 0 },
                repsTarget = firstSet?.repsText.orEmpty(),
                weightTarget = firstSet?.weight,
                restTargetSeconds = firstSet?.restSeconds,
                durationTargetSeconds = firstSet?.durationSeconds,
                distanceTargetKm = firstSet?.distanceKm,
                paceTargetSecondsPerKm = firstSet?.paceSecondsPerKm?.roundToInt(),
                rpeTarget = firstSet?.rpe,
                notes = firstSet?.notes.orEmpty(),
                supersetId = "",
                hints = if (!entry.isCombo) getPerformanceHint(entry.exerciseName) else PerformanceHint(),
            )
        }
    }

    fun buildSessionFromTemplate(
        template: WorkoutTemplate,
        date: String = LocalDate.now().format(DATE_FORMAT),
        useEngine: Boolean = true,
    ): WorkoutSession {
        @Suppress("NAME_SHADOWING")
        val useEngine = useEngine && isCoachEnabled()
        val defaults = latestSessionDefaults()
        val settings = getWorkoutPlanSettings()
        val goals = getTrainingGoals().filterNot { it.isAchieved }
        return WorkoutSession(
            name = template.name,
            workoutDate = date,
            sourceTemplateId = template.id.takeIf { it != 0L },
            status = SessionStatus.DRAFT,
            notes = template.notes,
            diet = defaults.diet,
            bodyWeightKg = defaults.bodyWeightKg,
            items = run {
                val picked = mutableSetOf<String>()
                template.items.mapIndexed { index, item ->
                    buildSessionItemFromTemplateItem(item, index, settings, goals, useEngine, picked)
                }
            },
        )
    }

    /**
     * Re-run the engine over a scheduled draft on its day. The ingest builds
     * sessions on Sunday with Sunday's history — a week of training must
     * re-tune Friday's numbers when Friday opens. Conservative on purpose:
     * only a completely untouched draft (zero logged sets) whose items still
     * mirror its template re-prescribes; anything he's edited stays his.
     */
    fun represcribeSessionForToday(session: WorkoutSession): WorkoutSession {
        val templateId = session.sourceTemplateId ?: return session
        if (session.items.any { it.loggedSets.isNotEmpty() }) return session
        if (!isCoachEnabled()) return session
        val template = getTemplates().firstOrNull { it.id == templateId } ?: return session
        if (template.items.size != session.items.size) return session
        val settings = getWorkoutPlanSettings()
        val goals = getTrainingGoals()
        val picked = mutableSetOf<String>()
        val fresh = template.items.mapIndexed { index, item ->
            buildSessionItemFromTemplateItem(item, index, settings, goals, useEngine = true, sessionPicks = picked)
        }
        return session.copy(
            items = session.items.mapIndexed { index, old ->
                val f = fresh.getOrNull(index) ?: return@mapIndexed old
                old.copy(
                    exerciseName = f.exerciseName,
                    comboId = f.comboId,
                    comboName = f.comboName,
                    progressionRole = f.progressionRole,
                    plannedSets = f.plannedSets,
                    targetReps = f.targetReps,
                    targetWeight = f.targetWeight,
                    targetRestSeconds = f.targetRestSeconds,
                    targetDurationSeconds = f.targetDurationSeconds,
                    targetDistanceKm = f.targetDistanceKm,
                    targetPaceSecondsPerKm = f.targetPaceSecondsPerKm,
                    targetRpe = f.targetRpe,
                    notes = f.notes,
                    supersetId = f.supersetId,
                    coachNote = f.coachNote,
                    hints = f.hints,
                )
            },
        )
    }

    /**
     * Turn one template item into a session item, letting the progression
     * engine prescribe sets × reps × weight (for lifts) or the next run (for
     * running items). Falls back to the raw template targets for anything the
     * engine doesn't understand (combos, timed mobility work, etc.).
     */
    private fun buildSessionItemFromTemplateItem(
        item: WorkoutTemplateItem,
        index: Int,
        settings: WorkoutPlanSettings,
        goals: List<TrainingGoal>,
        useEngine: Boolean = true,
        sessionPicks: MutableSet<String> = mutableSetOf(),
    ): WorkoutSessionItem {
        // Muscle-group slots ("Chest" + role) resolve to a concrete exercise
        // from the pool — least-recently-done first, excluding what this
        // session already picked, so workouts stay varied.
        var slotNote = ""
        var resolvedName = item.exerciseName
        if (item.comboId == null && item.exerciseName.isNotBlank()) {
            resolveMuscleGroupSlot(item.exerciseName, item.progressionRole, sessionPicks)?.let { (chosen, note) ->
                resolvedName = chosen
                slotNote = note
            }
        }
        if (resolvedName.isNotBlank()) sessionPicks += resolvedName
        @Suppress("NAME_SHADOWING")
        val item = if (resolvedName == item.exerciseName) item else item.copy(exerciseName = resolvedName)
        val exercise = item.exerciseName.takeIf { it.isNotBlank() }?.let(::getExerciseByName)
        val category = exercise?.defaultCategory.orEmpty()
        val isRunning = item.progressionRole == ProgressionRole.RUNNING || category.equals("Running", true)
        val isLift = !isRunning &&
            item.comboId == null &&
            item.exerciseName.isNotBlank() &&
            !category.equals("Martial Arts", true) &&
            (exercise == null || exercise.logFormat == LogFormat.REPS)

        var plannedSets = item.setsTarget
        var targetReps = item.repsTarget
        var targetWeight = item.weightTarget
        var targetDuration = item.durationTargetSeconds
        var targetDistance = item.distanceTargetKm
        var targetPace = item.paceTargetSecondsPerKm
        var coachNote = ""

        if (isLift && useEngine) {
            val goal = goals.firstOrNull {
                it.kind == GoalKind.LIFT_TARGET && it.exerciseName.equals(item.exerciseName, ignoreCase = true)
            }
            val step = when (item.progressionRole) {
                ProgressionRole.PRIMARY -> settings.primaryWeightStepKg
                ProgressionRole.SECONDARY -> settings.secondaryWeightStepKg
                else -> 2.5
            }
            val bodyweight = exercise?.isBodyweight == true
            val prescription = ProgressionEngine.prescribeLift(
                history = getLiftHistory(item.exerciseName, includeWeightless = bodyweight),
                role = item.progressionRole,
                settings = settings,
                stepKg = step,
                exerciseCategory = category,
                isCompound = exercise.isCompoundMovement(fallbackName = item.exerciseName),
                isBodyweight = bodyweight,
                templateSets = item.setsTarget,
                templateReps = parseLeadingInt(item.repsTarget),
                templateWeight = item.weightTarget,
                goalWeightKg = goal?.targetWeightKg,
            )
            plannedSets = prescription.sets
            targetReps = prescription.reps.toString()
            targetWeight = prescription.weightKg ?: targetWeight
            coachNote = prescription.rationale
        } else if (isRunning && useEngine) {
            val timeGoal = goals.firstOrNull { it.kind == GoalKind.RUN_TIME }
            val distanceGoal = goals.firstOrNull { it.kind == GoalKind.RUN_DISTANCE }
            val prescription = ProgressionEngine.prescribeRun(
                recentRuns = getRunHistoryRecords(limit = 30),
                goalTime = timeGoal,
                goalDistanceKm = distanceGoal?.targetDistanceKm,
                weeklyKmTarget = settings.weeklyRunKmTarget,
            )
            targetDistance = prescription.distanceKm ?: targetDistance
            targetPace = prescription.paceSecondsPerKm ?: targetPace
            targetDuration = prescription.durationSeconds ?: targetDuration
            coachNote = prescription.rationale
        }
        if (slotNote.isNotBlank()) {
            coachNote = listOf(slotNote, coachNote).filter { it.isNotBlank() }.joinToString(" ")
        }

        return WorkoutSessionItem(
            id = -(index + 1L),
            sortOrder = index,
            exerciseName = item.exerciseName,
            comboId = item.comboId,
            comboName = item.comboName,
            progressionRole = item.progressionRole,
            plannedSets = plannedSets,
            targetReps = targetReps,
            targetWeight = targetWeight,
            targetRestSeconds = item.restTargetSeconds,
            targetDurationSeconds = targetDuration,
            targetDistanceKm = targetDistance,
            targetPaceSecondsPerKm = targetPace,
            targetRpe = item.rpeTarget,
            notes = item.notes,
            supersetId = item.supersetId,
            coachNote = coachNote,
            hints = if (item.exerciseName.isNotBlank()) item.hints.takeIf { it != PerformanceHint() } ?: getPerformanceHint(item.exerciseName) else PerformanceHint(),
            // Deliberately NO pre-seeded sets: the plan lives in plannedSets and
            // the target fields, and the ledger fills only when a set is actually
            // logged. Pre-seeding made scheduled workouts look already-done and
            // defeated stashSession's ghost-draft guard.
            loggedSets = emptyList(),
        )
    }

    fun emptySession(date: String = LocalDate.now().format(DATE_FORMAT)): WorkoutSession {
        val defaults = latestSessionDefaults()
        return WorkoutSession(
            name = "",
            workoutDate = date,
            status = SessionStatus.DRAFT,
            diet = defaults.diet,
            bodyWeightKg = defaults.bodyWeightKg,
        )
    }

    fun emptyTemplate(): WorkoutTemplate = WorkoutTemplate(
        name = "",
        items = emptyList(),
    )

    // ───────────────────────── Martial-arts form progress ─────────────────────────

    /**
     * Read the user's progress through [formId] as a single snapshot. Forms with
     * no rows yet return an empty progress map; UI code can still render the
     * form against a fresh state and write rows back via [setMartialArtsMoveLearned].
     */
    fun loadMartialArtsFormProgress(formId: String): MartialArtsFormProgress {
        val form = getMartialArtsFormById(formId) ?: return MartialArtsFormProgress(formId = formId, totalMoves = 0)
        val map = mutableMapOf<Int, MartialArtsMoveProgress>()
        readableDb().rawQuery(
            """
            SELECT move_index, COALESCE(learned_at, '') AS learned_at, COALESCE(notes, '') AS notes
            FROM martial_arts_form_progress
            WHERE form_id = ?
            """.trimIndent(),
            arrayOf(formId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val moveIndex = cursor.int("move_index")
                // Rows beyond the form's live move range are orphans from an
                // edit that shortened it — counting them pushed percent > 100%.
                if (moveIndex !in 1..form.moveCount) continue
                map[moveIndex] = MartialArtsMoveProgress(
                    moveIndex = moveIndex,
                    learnedAt = cursor.string("learned_at"),
                    notes = cursor.string("notes"),
                )
            }
        }
        return MartialArtsFormProgress(
            formId = formId,
            totalMoves = form.moveCount,
            progressByIndex = map,
        )
    }

    /**
     * Mark a single move as learned (or unmark it if [learned] is false).
     * Inserts a new row when none exists, or updates the existing row's
     * `learned_at` while preserving any notes the user may have attached.
     *
     * Auto-export is triggered so the new state is captured in the next
     * timestamped backup, matching the rest of the data layer.
     */
    fun setMartialArtsMoveLearned(formId: String, moveIndex: Int, learned: Boolean) {
        val database = writableDb()
        val now = if (learned) nowStamp() else ""
        database.execSQL(
            """
            INSERT INTO martial_arts_form_progress (form_id, move_index, learned_at, notes)
            VALUES (?, ?, ?, '')
            ON CONFLICT(form_id, move_index) DO UPDATE SET learned_at = excluded.learned_at
            """.trimIndent(),
            arrayOf(formId, moveIndex.toString(), now),
        )
        autoExportDatabaseIfConfigured()
    }

    // ───────────────────────── 印谱 — the seal album (R22) ─────────────────────────

    /** Seal id → the day it was earned (yyyy-MM-dd). */
    fun loadSealAwards(): Map<String, String> {
        val awards = mutableMapOf<String, String>()
        readableDb().rawQuery("SELECT id, earned_at FROM seal_awards", null).use { cursor ->
            while (cursor.moveToNext()) awards[cursor.string("id")] = cursor.string("earned_at")
        }
        return awards
    }

    /** Record newly earned seals. Earned seals are never re-dated or revoked. */
    fun awardSeals(ids: Collection<String>, date: String = java.time.LocalDate.now().toString()) {
        if (ids.isEmpty()) return
        val database = writableDb()
        database.beginTransaction()
        try {
            ids.forEach { id ->
                database.execSQL(
                    "INSERT OR IGNORE INTO seal_awards (id, earned_at) VALUES (?, ?)",
                    arrayOf(id, date),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        autoExportDatabaseIfConfigured()
    }

    // ───────────────────────── 三十六房 — the 36 Chambers (R21) ─────────────────────────

    /** Chamber number → the day it was passed (yyyy-MM-dd). */
    fun loadChamberPasses(): Map<Int, String> {
        val passes = mutableMapOf<Int, String>()
        readableDb().rawQuery("SELECT number, passed_at FROM chamber_passes", null).use { cursor ->
            while (cursor.moveToNext()) passes[cursor.int("number")] = cursor.string("passed_at")
        }
        return passes
    }

    /** Seal a chamber as passed today, or reopen it. */
    fun setChamberPassed(number: Int, passed: Boolean, date: String = java.time.LocalDate.now().toString()) {
        val database = writableDb()
        if (passed) {
            database.execSQL(
                """
                INSERT INTO chamber_passes (number, passed_at, note) VALUES (?, ?, '')
                ON CONFLICT(number) DO UPDATE SET passed_at = excluded.passed_at
                """.trimIndent(),
                arrayOf(number.toString(), date),
            )
        } else {
            database.execSQL("DELETE FROM chamber_passes WHERE number = ?", arrayOf(number.toString()))
        }
        autoExportDatabaseIfConfigured()
    }

    /**
     * Where you stand on [chamber]'s measurable target, read from history.
     * Read-only; any failure (an older database without a column, say) reads
     * as "nothing measured yet" rather than breaking the Hall.
     */
    fun chamberProgress(chamber: Chamber): ChamberProgress {
        val metric = chamber.metric
        return runCatching {
            when (metric.kind) {
                ChamberMetricKind.MANUAL -> ChamberProgress()
                ChamberMetricKind.FORM_MOVES -> {
                    val form = getMartialArtsFormById(metric.formId)
                    if (form == null || form.moveCount == 0) {
                        ChamberProgress(label = "Add the form to Lìliàng to track it")
                    } else {
                        val target = if (metric.target > 0) metric.target else kotlin.math.ceil(form.moveCount * metric.formFraction)
                        val reached = maxOf(maxCheckpointMove(form.id), loadMartialArtsFormProgress(form.id).learnedCount)
                        ChamberProgress(reached.toDouble(), target, "Move $reached of ${target.toInt()}")
                    }
                }
                ChamberMetricKind.HOLD_SECONDS -> {
                    val best = maxLoggedDuration(metric.exercises)
                    ChamberProgress(best, metric.target, "${ChamberCatalog.formatSeconds(best)} of ${ChamberCatalog.formatSeconds(metric.target)}")
                }
                ChamberMetricKind.ROUNDS -> {
                    val best = mostRoundsInADay(metric.exercises, metric.minSeconds)
                    ChamberProgress(best.toDouble(), metric.target, "$best of ${metric.target.toInt()} rounds")
                }
                ChamberMetricKind.REPS -> {
                    val best = maxLoggedReps(metric.exercises)
                    ChamberProgress(best.toDouble(), metric.target, "$best of ${metric.target.toInt()}")
                }
                ChamberMetricKind.DISTANCE_KM -> {
                    val best = longestRunKm()
                    ChamberProgress(best, metric.target, "%.1f of %.0f km".format(best, metric.target))
                }
            }
        }.getOrElse { ChamberProgress() }
    }

    /** "(Exercise = ? OR Exercise LIKE ?) OR …" — a name matches itself and its "Name: …" rows. */
    private fun chamberExerciseClause(names: List<String>): Pair<String, Array<String>> {
        val clause = names.joinToString(" OR ", prefix = "(", postfix = ")") { "(Exercise = ? OR Exercise LIKE ?)" }
        val args = names.flatMap { listOf(it, "$it: %") }
        return clause to args.toTypedArray()
    }

    private fun maxCheckpointMove(formId: String): Int =
        readableDb().rawQuery(
            "SELECT COALESCE(MAX(move_index), 0) AS m FROM martial_arts_form_checkpoints WHERE form_id = ?",
            arrayOf(formId),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun maxLoggedDuration(names: List<String>): Double {
        if (names.isEmpty()) return 0.0
        val (clause, args) = chamberExerciseClause(names)
        return readableDb().rawQuery("SELECT COALESCE(MAX(DurationSeconds), 0) FROM lifts_new WHERE $clause", args)
            .use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private fun mostRoundsInADay(names: List<String>, minSeconds: Int): Int {
        if (names.isEmpty()) return 0
        val (clause, args) = chamberExerciseClause(names)
        return readableDb().rawQuery(
            "SELECT COUNT(*) FROM lifts_new WHERE $clause AND COALESCE(DurationSeconds, 0) >= ? GROUP BY Date ORDER BY COUNT(*) DESC LIMIT 1",
            args + minSeconds.toString(),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun maxLoggedReps(names: List<String>): Int {
        if (names.isEmpty()) return 0
        val (clause, args) = chamberExerciseClause(names)
        return readableDb().rawQuery("SELECT COALESCE(MAX(CAST(Reps AS INTEGER)), 0) FROM lifts_new WHERE $clause", args)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun longestRunKm(): Double =
        readableDb().rawQuery(
            "SELECT COALESCE(MAX(DistanceKm), 0) FROM lifts_new WHERE DistanceKm IS NOT NULL AND Exercise LIKE '%Run%'",
            null,
        ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    // ───────────────────────── Progression-engine inputs ─────────────────────────

    /**
     * Per-session history of one exercise, newest first, for the progression
     * engine. Only rows with parseable weight + reps count — a session that
     * logged nothing usable simply doesn't appear.
     */
    fun getLiftHistory(exerciseName: String, limit: Int = 12, includeWeightless: Boolean = false): LiftHistory {
        val sessionsByDate = LinkedHashMap<String, MutableList<SetPerformance>>()
        // Bodyweight movements log reps with no Weight — requiring a weight
        // made them permanently "no history" to the engine (audit #22).
        val weightFilter = if (includeWeightless) "" else "AND Weight IS NOT NULL"
        readableDb().rawQuery(
            """
            SELECT Date, Weight, Reps, RPE
            FROM lifts_new
            WHERE Exercise = ? COLLATE NOCASE
              $weightFilter
              AND Reps IS NOT NULL
            ORDER BY Date DESC, id ASC
            """.trimIndent(),
            arrayOf(exerciseName.trim()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val date = cursor.string("Date")
                if (!sessionsByDate.containsKey(date) && sessionsByDate.size >= limit) break
                sessionsByDate.getOrPut(date) { mutableListOf() } += SetPerformance(
                    weight = cursor.doubleOrNull("Weight"),
                    reps = cursor.intOrNull("Reps"),
                    rpe = cursor.intOrNull("RPE"),
                )
            }
        }
        return LiftHistory(
            exerciseName = exerciseName,
            sessions = sessionsByDate.map { (date, sets) -> LiftSession(date = date, sets = sets) },
        )
    }

    /** Recent runs, newest first, for the running side of the engine. */
    fun getRunHistoryRecords(limit: Int = 30): List<RunRecord> {
        val runs = mutableListOf<RunRecord>()
        readableDb().rawQuery(
            """
            SELECT Date, DistanceKm, DurationSeconds, PaceSecondsPerKm
            FROM lifts_new
            WHERE LOWER(COALESCE(Category, '')) = 'running'
              AND DistanceKm IS NOT NULL
            ORDER BY Date DESC, id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runs += RunRecord(
                    date = cursor.string("Date"),
                    distanceKm = cursor.doubleOrNull("DistanceKm") ?: 0.0,
                    durationSeconds = cursor.intOrNull("DurationSeconds"),
                    paceSecondsPerKm = cursor.doubleOrNull("PaceSecondsPerKm"),
                )
            }
        }
        return runs
    }

    /**
     * Resolve a muscle-group "slot" into a concrete exercise, for the
     * workouts-with-variety flow: a template item named just "Chest" with
     * role PRIMARY picks the primary chest movement that was performed
     * least recently (never-done exercises first). Returns the chosen
     * exercise plus a one-line rationale, or null when [name] isn't a known
     * muscle group or no pool candidate exists.
     */
    fun resolveMuscleGroupSlot(name: String, role: ProgressionRole, exclude: Set<String> = emptySet()): Pair<String, String>? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        // It's only a slot if it isn't a real exercise but is a known muscle group.
        if (getExerciseByName(trimmed) != null) return null
        val knownGroups = WorkoutVocab.merge(WorkoutVocab.muscles, getMuscleGroups())
        val group = knownGroups.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: return null

        val poolRole = if (role == ProgressionRole.NONE) ProgressionRole.ACCESSORY else role
        ensureMuscleGroupPoolSeeded(group, poolRole)
        val pool = getMuscleGroupRules()
            .filter { it.muscleGroup.equals(group, ignoreCase = true) && it.role == poolRole }
            .map { it.exerciseName }
            .ifEmpty { getSeedExercisesForMuscleGroup(group) }
        if (pool.isEmpty()) return null

        // Two same-role slots in one template used to resolve identically —
        // recency doesn't change between resolutions, so exclude what the
        // session already picked (falling back to repeats only if the pool runs dry).
        val available = pool
            .filterNot { candidate -> exclude.any { it.equals(candidate, ignoreCase = true) } }
            .ifEmpty { pool }
        val byRecency = available.associateWith { exerciseName -> lastPerformedDate(exerciseName).orEmpty() }
        val chosen = available.minByOrNull { byRecency[it].orEmpty() } ?: return null
        val lastDone = byRecency[chosen].orEmpty()
        val rationale = if (lastDone.isBlank()) {
            "$group slot → $chosen (never logged — fresh stimulus)."
        } else {
            "$group slot → $chosen (least recent in your pool, last done $lastDone)."
        }
        return chosen to rationale
    }

    private fun lastPerformedDate(exerciseName: String): String? =
        readableDb().rawQuery(
            "SELECT MAX(Date) AS last_date FROM lifts_new WHERE Exercise = ? COLLATE NOCASE",
            arrayOf(exerciseName.trim()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.string("last_date").takeIf { it.isNotBlank() } else null
        }

    // ───────────────────────── Coach switch ─────────────────────────

    /**
     * Master switch for the progression engine's voice. Off means: sessions
     * start from raw template targets, no coach notes, no day-detail plan
     * preview, no run suggestions — the app records, you decide.
     */
    fun isCoachEnabled(): Boolean =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(COACH_ENABLED_KEY, true)

    fun setCoachEnabled(enabled: Boolean) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(COACH_ENABLED_KEY, enabled).apply()
    }

    /** Rest-over chime: one tick + buzz when the count-up crosses the plan's
     *  rest (the phone is on the floor — the gold→vermillion flip alone is a
     *  signal on a screen nobody is looking at). Default ON; opt out in
     *  Settings. */
    fun isRestChimeEnabled(): Boolean =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(REST_CHIME_KEY, true)

    fun setRestChimeEnabled(enabled: Boolean) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(REST_CHIME_KEY, enabled).apply()
    }

    /**
     * The role an exercise plays, looked up from the muscle-rule pools it
     * belongs to — an exercise in a PRIMARY pool is a primary lift wherever
     * it's added. Highest role wins when it sits in several pools; exercises
     * in no pool default to ACCESSORY.
     */
    fun roleForExercise(name: String): ProgressionRole {
        if (name.isBlank()) return ProgressionRole.ACCESSORY
        // An explicit per-exercise default wins — set in Settings → Exercise roles.
        val explicit = getExerciseByName(name.trim())?.defaultRole
        if (explicit != null && explicit != ProgressionRole.NONE) return explicit
        val roles = getMuscleGroupRules()
            .filter { it.exerciseName.equals(name.trim(), ignoreCase = true) }
            .map { it.role }
        return when {
            ProgressionRole.PRIMARY in roles -> ProgressionRole.PRIMARY
            ProgressionRole.SECONDARY in roles -> ProgressionRole.SECONDARY
            ProgressionRole.RUNNING in roles -> ProgressionRole.RUNNING
            else -> ProgressionRole.ACCESSORY
        }
    }

    /**
     * Engine prescription for a single exercise added mid-session — same
     * inputs as template building, so an ad-hoc exercise gets the same
     * coaching as a planned one. Returns null for non-lift entries (combos,
     * running, martial arts, timed work).
     */
    fun getLiftPrescriptionForExercise(name: String, role: ProgressionRole = ProgressionRole.ACCESSORY): LiftPrescription? {
        if (!isCoachEnabled()) return null
        if (name.isBlank()) return null
        val exercise = getExerciseByName(name)
        val category = exercise?.defaultCategory.orEmpty()
        if (category.equals("Running", true) || category.equals("Martial Arts", true)) return null
        if (exercise != null && exercise.logFormat != LogFormat.REPS) return null
        val settings = getWorkoutPlanSettings()
        val goal = getTrainingGoals().firstOrNull {
            !it.isAchieved && it.kind == GoalKind.LIFT_TARGET && it.exerciseName.equals(name, ignoreCase = true)
        }
        val bodyweight = exercise?.isBodyweight == true
        return ProgressionEngine.prescribeLift(
            history = getLiftHistory(name, includeWeightless = bodyweight),
            role = role,
            settings = settings,
            exerciseCategory = category,
            isCompound = exercise.isCompoundMovement(fallbackName = name),
            isBodyweight = bodyweight,
            goalWeightKg = goal?.targetWeightKg,
        )
    }

    /**
     * The engine's suggestion for the next standalone run, assembled from
     * recent history, active running goals, and the weekly volume target.
     */
    fun getRunPrescription(): RunPrescription {
        val goals = getTrainingGoals().filterNot { it.isAchieved }
        val settings = getWorkoutPlanSettings()
        return ProgressionEngine.prescribeRun(
            recentRuns = getRunHistoryRecords(limit = 30),
            goalTime = goals.firstOrNull { it.kind == GoalKind.RUN_TIME },
            goalDistanceKm = goals.firstOrNull { it.kind == GoalKind.RUN_DISTANCE }?.targetDistanceKm,
            weeklyKmTarget = settings.weeklyRunKmTarget,
        )
    }

    // ───────────────────────── Training goals ─────────────────────────

    fun getTrainingGoals(): List<TrainingGoal> {
        val goals = mutableListOf<TrainingGoal>()
        readableDb().rawQuery(
            """
            SELECT id, kind, title, COALESCE(exercise_name, '') AS exercise_name,
                   COALESCE(discipline, '') AS discipline, COALESCE(form_id, '') AS form_id,
                   target_weight_kg, target_distance_km, target_duration_seconds,
                   target_sessions_per_week, COALESCE(goal_cadence, '') AS goal_cadence,
                   COALESCE(target_date, '') AS target_date,
                   COALESCE(notes, '') AS notes, created_at, COALESCE(achieved_at, '') AS achieved_at
            FROM training_goals
            ORDER BY CASE WHEN COALESCE(achieved_at, '') = '' THEN 0 ELSE 1 END, sort_order, id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val cadence = cursor.string("goal_cadence").split(":")
                goals += TrainingGoal(
                    id = cursor.long("id"),
                    kind = GoalKind.fromDb(cursor.string("kind")),
                    title = cursor.string("title"),
                    exerciseName = cursor.string("exercise_name"),
                    discipline = cursor.string("discipline"),
                    formId = cursor.string("form_id"),
                    targetWeightKg = cursor.doubleOrNull("target_weight_kg"),
                    targetDistanceKm = cursor.doubleOrNull("target_distance_km"),
                    targetDurationSeconds = cursor.intOrNull("target_duration_seconds"),
                    targetSessionsPerWeek = cursor.intOrNull("target_sessions_per_week"),
                    cadenceScope = CadenceScope.fromDb(cadence.getOrNull(0)),
                    cadencePeriod = CadencePeriod.fromDb(cadence.getOrNull(1)),
                    targetDate = cursor.string("target_date"),
                    notes = cursor.string("notes"),
                    createdAt = cursor.string("created_at"),
                    achievedAt = cursor.string("achieved_at"),
                )
            }
        }
        return goals
    }

    fun saveTrainingGoal(goal: TrainingGoal): Long {
        if (goal.title.isBlank()) error("Goal needs a title")
        val database = writableDb()
        val values = ContentValues().apply {
            put("kind", goal.kind.dbValue)
            put("title", goal.title.trim())
            put("exercise_name", goal.exerciseName.trim().takeIf { it.isNotBlank() })
            put("discipline", goal.discipline.trim().takeIf { it.isNotBlank() })
            put("form_id", goal.formId.trim().takeIf { it.isNotBlank() })
            put("target_weight_kg", goal.targetWeightKg)
            put("target_distance_km", goal.targetDistanceKm)
            put("target_duration_seconds", goal.targetDurationSeconds)
            put("target_sessions_per_week", goal.targetSessionsPerWeek)
            put(
                "goal_cadence",
                if (goal.kind == GoalKind.CADENCE) {
                    "${goal.cadenceScope.dbValue}:${goal.cadencePeriod.dbValue}"
                } else null,
            )
            put("target_date", goal.targetDate.takeIf { it.isNotBlank() })
            put("notes", goal.notes.trim().takeIf { it.isNotBlank() })
            put("achieved_at", goal.achievedAt.takeIf { it.isNotBlank() })
        }
        val id = if (goal.id == 0L) {
            values.put("created_at", nowStamp())
            database.insertOrThrow("training_goals", null, values)
        } else {
            database.update("training_goals", values, "id = ?", arrayOf(goal.id.toString()))
            goal.id
        }
        invalidateDashboardKpiCache()
        autoExportDatabaseIfConfigured()
        return id
    }

    fun deleteTrainingGoal(goalId: Long) {
        writableDb().delete("training_goals", "id = ?", arrayOf(goalId.toString()))
        invalidateDashboardKpiCache()
        autoExportDatabaseIfConfigured()
    }

    fun setTrainingGoalAchieved(goalId: Long, achieved: Boolean) {
        writableDb().update(
            "training_goals",
            ContentValues().apply {
                if (achieved) put("achieved_at", nowStamp()) else putNull("achieved_at")
            },
            "id = ?",
            arrayOf(goalId.toString()),
        )
        invalidateDashboardKpiCache()
        autoExportDatabaseIfConfigured()
    }

    /**
     * Compute live progress for every goal — current value, percent toward
     * target, trend-based ETA. The heavier lookups (lift history, runs, body
     * weight) only run for goals that need them.
     */
    @Volatile
    private var cachedGoalProgress: List<GoalProgress>? = null

    fun getGoalProgressList(): List<GoalProgress> {
        cachedGoalProgress?.let { return it }
        return computeGoalProgressList().also { cachedGoalProgress = it }
    }

    private fun computeGoalProgressList(): List<GoalProgress> = getTrainingGoals().map { goal ->
        when (goal.kind) {
            GoalKind.LIFT_TARGET -> liftGoalProgress(goal)
            GoalKind.RUN_TIME -> runTimeGoalProgress(goal)
            GoalKind.RUN_DISTANCE -> runDistanceGoalProgress(goal)
            GoalKind.BODYWEIGHT -> bodyweightGoalProgress(goal)
            GoalKind.CADENCE -> cadenceGoalProgress(goal)
            GoalKind.DISCIPLINE_SESSIONS -> disciplineGoalProgress(goal)
            GoalKind.FORM_MASTERY -> formGoalProgress(goal)
            GoalKind.INSPIRATION -> GoalProgress(goal = goal, detail = goal.notes)
        }
    }

    /**
     * "Train N times this week/month" — count distinct training days in the
     * rolling window (all workouts, or just runs) against the target. Carries
     * the raw counts so the dashboard can draw a tally of dots.
     */
    private fun cadenceGoalProgress(goal: TrainingGoal): GoalProgress {
        val target = goal.targetSessionsPerWeek ?: return GoalProgress(goal = goal)
        if (target <= 0) return GoalProgress(goal = goal)
        val period = goal.cadencePeriod
        val since = LocalDate.now().minusDays((period.days - 1).toLong()).format(DATE_FORMAT)
        val count = when (goal.cadenceScope) {
            CadenceScope.RUNNING -> countRunDaysSince(since)
            CadenceScope.ALL -> countTrainingDaysSince(since)
        }
        val periodWord = if (period == CadencePeriod.WEEK) "this week" else "this month"
        val perWord = if (period == CadencePeriod.WEEK) "/week" else "/month"
        return GoalProgress(
            goal = goal,
            currentLabel = "$count $periodWord",
            targetLabel = "$target$perWord",
            percent = (count.toFloat() / target).coerceIn(0f, 1f),
            currentCount = count,
            targetCount = target,
        )
    }

    /** Distinct days with any logged training since [sinceDate] (inclusive). */
    fun countTrainingDaysSince(sinceDate: String): Int {
        readableDb().rawQuery(
            "SELECT COUNT(DISTINCT Date) AS days FROM lifts_new WHERE Date >= ?",
            arrayOf(sinceDate),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.int("days") else 0
        }
    }

    /** Distinct days with a run logged since [sinceDate] (inclusive). */
    fun countRunDaysSince(sinceDate: String): Int {
        readableDb().rawQuery(
            """
            SELECT COUNT(DISTINCT Date) AS days
            FROM lifts_new
            WHERE Date >= ?
              AND (LOWER(COALESCE(Category, '')) = 'running' OR LOWER(Exercise) LIKE '%run%')
            """.trimIndent(),
            arrayOf(sinceDate),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.int("days") else 0
        }
    }

    private fun liftGoalProgress(goal: TrainingGoal): GoalProgress {
        val target = goal.targetWeightKg ?: return GoalProgress(goal = goal)
        val history = getLiftHistory(goal.exerciseName, limit = 40)
        val sessions = history.sessions.filter { it.bestE1rm != null }
        val bestE1rm = sessions.mapNotNull { it.bestE1rm }.maxOrNull()
        val bestSingleWeight = sessions.flatMap { it.sets }.mapNotNull { it.weight }.maxOrNull()
        val trendPoints = sessions.mapNotNull { session ->
            runCatching { LocalDate.parse(session.date) to (session.bestE1rm ?: return@mapNotNull null) }.getOrNull()
        }.sortedBy { it.first }
        val slope = trendSlopePerDay(trendPoints.takeLast(12))
        val eta = bestE1rm?.let { projectEta(it, target, slope) }
        val percent = bestE1rm?.let { (it / target).toFloat().coerceIn(0f, 1f) } ?: 0f
        return GoalProgress(
            goal = goal,
            currentLabel = bestE1rm?.let { "e1RM ${formatWeight(it)}" } ?: "No lifts yet",
            targetLabel = formatWeight(target),
            percent = percent,
            detail = buildString {
                if (bestSingleWeight != null) append("Heaviest set ${formatWeight(bestSingleWeight)}")
                if (slope != null && slope > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("+${formatWeight(slope * 7)}/week")
                }
            },
            etaLabel = eta?.format(DATE_FORMAT) ?: "",
        )
    }

    private fun runTimeGoalProgress(goal: TrainingGoal): GoalProgress {
        val targetDistance = goal.targetDistanceKm ?: return GoalProgress(goal = goal)
        val targetDuration = goal.targetDurationSeconds ?: return GoalProgress(goal = goal)
        if (targetDistance <= 0 || targetDuration <= 0) return GoalProgress(goal = goal)
        val runs = getRunHistoryRecords(limit = 60)
        // Best projected time for the goal distance from any comparable run
        // (≥60% of goal distance, with pace data).
        val bestProjected = runs
            .filter { it.distanceKm >= targetDistance * 0.6 && (it.paceSecondsPerKm ?: 0.0) > 0.0 }
            .minOfOrNull { it.paceSecondsPerKm!! * targetDistance }
            ?.takeIf { it > 0.0 }
        val percent = bestProjected?.let { (targetDuration / it).toFloat().coerceIn(0f, 1f) } ?: 0f
        return GoalProgress(
            goal = goal,
            currentLabel = bestProjected?.let { "~${formatDuration(it.roundToInt())}" } ?: "No comparable runs",
            targetLabel = "${formatDistance(targetDistance)} in ${formatDuration(targetDuration)}",
            percent = percent,
            detail = bestProjected?.let { "Projected from your best recent pace" }.orEmpty(),
        )
    }

    private fun runDistanceGoalProgress(goal: TrainingGoal): GoalProgress {
        val target = goal.targetDistanceKm ?: return GoalProgress(goal = goal)
        if (target <= 0) return GoalProgress(goal = goal)
        val longest = getRunHistoryRecords(limit = 100).maxOfOrNull { it.distanceKm } ?: 0.0
        return GoalProgress(
            goal = goal,
            currentLabel = if (longest > 0) "Longest ${formatDistance(longest)}" else "No runs yet",
            targetLabel = formatDistance(target),
            percent = (longest / target).toFloat().coerceIn(0f, 1f),
        )
    }

    private fun bodyweightGoalProgress(goal: TrainingGoal): GoalProgress {
        val target = goal.targetWeightKg ?: return GoalProgress(goal = goal)
        val historyNewestLast = getBodyWeightHistory()
        val current = historyNewestLast.lastOrNull()?.weightKg ?: return GoalProgress(
            goal = goal,
            targetLabel = formatWeight(target),
        )
        // Baseline: first weigh-in after the goal was created, else the oldest we have.
        val createdDate = goal.createdAt.take(10)
        val start = historyNewestLast.firstOrNull { it.date >= createdDate }?.weightKg
            ?: historyNewestLast.firstOrNull()?.weightKg ?: current
        val totalGap = target - start
        val percent = when {
            totalGap == 0.0 -> 1f
            else -> (((current - start) / totalGap).toFloat()).coerceIn(0f, 1f)
        }
        return GoalProgress(
            goal = goal,
            currentLabel = formatWeight(current),
            targetLabel = formatWeight(target),
            percent = percent,
            detail = "Started at ${formatWeight(start)}",
        )
    }

    private fun disciplineGoalProgress(goal: TrainingGoal): GoalProgress {
        val target = goal.targetSessionsPerWeek ?: return GoalProgress(goal = goal)
        if (target <= 0) return GoalProgress(goal = goal)
        val period = goal.cadencePeriod
        val since = LocalDate.now().minusDays((period.days - 1).toLong()).format(DATE_FORMAT)
        val count = countDisciplineSessionDays(goal.discipline, sinceDate = since)
        val periodWord = if (period == CadencePeriod.WEEK) "week" else "month"
        return GoalProgress(
            goal = goal,
            currentLabel = "$count this $periodWord",
            targetLabel = "$target/$periodWord",
            percent = (count.toFloat() / target).coerceIn(0f, 1f),
        )
    }

    private fun formGoalProgress(goal: TrainingGoal): GoalProgress {
        val progress = loadMartialArtsFormProgress(goal.formId)
        if (progress.totalMoves == 0) return GoalProgress(goal = goal)
        return GoalProgress(
            goal = goal,
            currentLabel = "${progress.learnedCount} of ${progress.totalMoves} moves",
            targetLabel = "Complete form",
            percent = progress.percent,
        )
    }

    /**
     * Distinct training days since [sinceDate] that touched [discipline] —
     * matched against the Discipline column, the exercise name itself
     * ("Tai Chi"), or combos belonging to the discipline.
     */
    fun countDisciplineSessionDays(discipline: String, sinceDate: String): Int {
        if (discipline.isBlank()) return 0
        readableDb().rawQuery(
            """
            SELECT COUNT(DISTINCT Date) AS days
            FROM lifts_new
            WHERE Date >= ?
              AND LOWER(COALESCE(Category, '')) = 'martial arts'
              AND (
                LOWER(COALESCE(Discipline, '')) = LOWER(?)
                OR LOWER(Exercise) = LOWER(?)
                OR Exercise LIKE ? || ':%'
                OR ComboID IN (SELECT id FROM combos WHERE LOWER(discipline) = LOWER(?))
              )
            """.trimIndent(),
            arrayOf(sinceDate, discipline, discipline, discipline, discipline),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.int("days") else 0
        }
    }

    // ───────────────────────── Martial-arts hall ─────────────────────────

    /** The runtime forms catalogue: DB rows with their move lists, in display order. */
    fun getMartialArtsFormsCatalog(): List<MartialArtsForm> {
        val movesByForm = mutableMapOf<String, MutableList<MartialArtsMove>>()
        readableDb().rawQuery(
            "SELECT form_id, move_index, name, COALESCE(section, '') AS section FROM martial_arts_form_moves ORDER BY form_id, move_index",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                movesByForm.getOrPut(cursor.string("form_id")) { mutableListOf() } += MartialArtsMove(
                    index = cursor.int("move_index"),
                    name = cursor.string("name"),
                    section = cursor.string("section"),
                )
            }
        }
        val forms = mutableListOf<MartialArtsForm>()
        readableDb().rawQuery(
            """
            SELECT id, title, COALESCE(subtitle, '') AS subtitle, COALESCE(discipline, '') AS discipline,
                   COALESCE(short_title, '') AS short_title, COALESCE(mastered_at, '') AS mastered_at
            FROM martial_arts_forms
            ORDER BY sort_order, id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.string("id")
                forms += MartialArtsForm(
                    id = id,
                    title = cursor.string("title"),
                    subtitle = cursor.string("subtitle"),
                    discipline = cursor.string("discipline"),
                    shortTitle = cursor.string("short_title"),
                    masteredAt = cursor.string("mastered_at"),
                    moves = movesByForm[id].orEmpty(),
                )
            }
        }
        return forms
    }

    fun getMartialArtsFormById(formId: String): MartialArtsForm? =
        getMartialArtsFormsCatalog().firstOrNull { it.id == formId }

    /**
     * Create or update a form, replacing its move list. New forms (blank id)
     * get a slug derived from the title, uniquified if needed.
     */
    fun saveMartialArtsForm(form: MartialArtsForm): String {
        if (form.title.isBlank()) error("Form needs a title")
        val database = writableDb()
        val id = form.id.ifBlank { uniqueFormSlug(form.title) }
        database.beginTransaction()
        try {
            val maxSort = database.rawQuery("SELECT COALESCE(MAX(sort_order), -1) AS m FROM martial_arts_forms", null)
                .use { if (it.moveToFirst()) it.int("m") else -1 }
            database.execSQL(
                """
                INSERT INTO martial_arts_forms (id, title, subtitle, discipline, short_title, sort_order, mastered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title, subtitle = excluded.subtitle, discipline = excluded.discipline,
                    short_title = excluded.short_title, mastered_at = excluded.mastered_at
                """.trimIndent(),
                arrayOf(
                    id, form.title.trim(), form.subtitle.trim(), form.discipline.trim(),
                    form.shortTitle.trim(), (maxSort + 1).toString(), form.masteredAt.takeIf { it.isNotBlank() },
                ),
            )
            database.delete("martial_arts_form_moves", "form_id = ?", arrayOf(id))
            form.moves.forEach { move ->
                database.execSQL(
                    "INSERT INTO martial_arts_form_moves (form_id, move_index, name, section) VALUES (?, ?, ?, ?)",
                    arrayOf(id, move.index.toString(), move.name, move.section),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        autoExportDatabaseIfConfigured()
        return id
    }

    fun deleteMartialArtsForm(formId: String) {
        val database = writableDb()
        database.beginTransaction()
        try {
            database.delete("martial_arts_form_moves", "form_id = ?", arrayOf(formId))
            database.delete("martial_arts_forms", "id = ?", arrayOf(formId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        autoExportDatabaseIfConfigured()
    }

    fun setFormMastered(formId: String, mastered: Boolean) {
        writableDb().execSQL(
            "UPDATE martial_arts_forms SET mastered_at = ? WHERE id = ?",
            arrayOf(if (mastered) nowStamp() else null, formId),
        )
        autoExportDatabaseIfConfigured()
    }

    /** Deterministic slug for a form title — what ingest upserts by when the
     *  JSON carries no id (re-applies must hit the same row, never a _2 clone). */
    private fun formSlugBase(title: String): String = title.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "form" }

    private fun uniqueFormSlug(title: String): String {
        val base = formSlugBase(title)
        val existing = readableDb().rawQuery("SELECT id FROM martial_arts_forms", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        if (base !in existing) return base
        var i = 2
        while ("${base}_$i" in existing) i++
        return "${base}_$i"
    }

    /** The runtime disciplines catalogue (user-extendable — Kendo-ready). */
    fun getMartialArtsDisciplinesCatalog(): List<MartialArtsDiscipline> {
        val disciplines = mutableListOf<MartialArtsDiscipline>()
        readableDb().rawQuery(
            "SELECT name, COALESCE(glyph, '') AS glyph, COALESCE(tagline, '') AS tagline FROM martial_arts_disciplines ORDER BY sort_order, name",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                disciplines += MartialArtsDiscipline(
                    name = cursor.string("name"),
                    glyph = cursor.string("glyph"),
                    tagline = cursor.string("tagline"),
                )
            }
        }
        return disciplines
    }

    /**
     * Remove a discipline card from the Hall. History rows are untouched — this
     * deletes the catalogue entry only (the typo'd-art escape hatch; there was
     * no way to remove one at all). Seeded arts return only on a fresh install.
     */
    fun deleteMartialArtsDiscipline(name: String) {
        if (name.isBlank()) return
        writableDb().delete("martial_arts_disciplines", "name = ?", arrayOf(name.trim()))
        autoExportDatabaseIfConfigured()
    }

    fun saveMartialArtsDiscipline(discipline: MartialArtsDiscipline) {
        if (discipline.name.isBlank()) error("Discipline needs a name")
        val maxSort = readableDb().rawQuery("SELECT COALESCE(MAX(sort_order), -1) AS m FROM martial_arts_disciplines", null)
            .use { if (it.moveToFirst()) it.int("m") else -1 }
        writableDb().execSQL(
            """
            INSERT INTO martial_arts_disciplines (name, glyph, tagline, sort_order)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET glyph = excluded.glyph, tagline = excluded.tagline
            """.trimIndent(),
            arrayOf(discipline.name.trim(), discipline.glyph.trim(), discipline.tagline.trim(), (maxSort + 1).toString()),
        )
        autoExportDatabaseIfConfigured()
    }

    /**
     * Everything the Martial Arts hall renders: the forms catalogue,
     * per-discipline hour/session totals, the recent training timeline, and
     * form checkpoints.
     */
    fun loadMartialArtsHall(): MartialArtsHallSnapshot {
        val forms = getMartialArtsFormsCatalog()
        val disciplines = getMartialArtsDisciplinesCatalog()
        return MartialArtsHallSnapshot(
            forms = forms,
            disciplines = disciplines,
            disciplineStats = disciplines.map { getDisciplineStats(it.name) },
            recentSessions = getRecentMartialArtsSessions(limit = 10),
            checkpointsByForm = forms.associate { it.id to getFormCheckpoints(it.id, limit = 8) },
        )
    }

    /**
     * Aggregate training stats for one discipline. Rows match on the
     * Discipline column, the exercise name itself ("Tai Chi"), or membership
     * of one of the discipline's combos — the same matching rule the goal
     * tracker uses.
     */
    fun getDisciplineStats(discipline: String): DisciplineStats {
        val yearStart = LocalDate.now().withDayOfYear(1).format(DATE_FORMAT)
        readableDb().rawQuery(
            """
            SELECT
                COALESCE(SUM(COALESCE(DurationSeconds, 0)), 0) AS total_seconds,
                COALESCE(SUM(CASE WHEN Date >= ? THEN COALESCE(DurationSeconds, 0) ELSE 0 END), 0) AS year_seconds,
                COUNT(DISTINCT CASE WHEN Date >= ? THEN Date END) AS year_sessions,
                COALESCE(MAX(Date), '') AS last_date
            FROM lifts_new
            WHERE LOWER(COALESCE(Category, '')) = 'martial arts'
              AND (
                -- Exact name or the "Parent: combo" prefix — the old substring
                -- match let "Boxing" absorb every Kickboxing/Shadowboxing row.
                LOWER(COALESCE(Discipline, '')) = LOWER(?)
                OR LOWER(Exercise) = LOWER(?)
                OR Exercise LIKE ? || ':%'
                OR ComboID IN (SELECT id FROM combos WHERE LOWER(discipline) = LOWER(?))
              )
            """.trimIndent(),
            arrayOf(yearStart, yearStart, discipline, discipline, discipline, discipline),
        ).use { cursor ->
            val comboCount = countCombosForDiscipline(discipline)
            if (!cursor.moveToFirst()) return DisciplineStats(name = discipline, comboCount = comboCount)
            return DisciplineStats(
                name = discipline,
                totalHours = cursor.long("total_seconds") / 3600.0,
                thisYearHours = cursor.long("year_seconds") / 3600.0,
                thisYearSessions = cursor.int("year_sessions"),
                lastTrainedDate = cursor.string("last_date"),
                comboCount = comboCount,
            )
        }
    }

    private fun countCombosForDiscipline(discipline: String): Int =
        readableDb().rawQuery(
            "SELECT COUNT(*) AS n FROM combos WHERE LOWER(discipline) = LOWER(?)",
            arrayOf(discipline),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.int("n") else 0 }

    /** Recent martial-arts sessions (date × exercise rollups), newest first. */
    fun getRecentMartialArtsSessions(limit: Int = 10): List<MartialArtsSessionRecord> {
        val sessions = mutableListOf<MartialArtsSessionRecord>()
        readableDb().rawQuery(
            """
            SELECT Date, Exercise, COALESCE(MAX(Discipline), '') AS Discipline,
                   COALESCE(SUM(COALESCE(DurationSeconds, 0)), 0) AS total_seconds,
                   COUNT(*) AS rounds
            FROM lifts_new
            WHERE LOWER(COALESCE(Category, '')) = 'martial arts'
            GROUP BY Date, Exercise
            ORDER BY Date DESC, MAX(id) DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sessions += MartialArtsSessionRecord(
                    date = cursor.string("Date"),
                    title = cursor.string("Exercise"),
                    discipline = cursor.string("Discipline"),
                    totalSeconds = cursor.long("total_seconds").toInt(),
                    rounds = cursor.int("rounds"),
                )
            }
        }
        return sessions
    }

    // ───────────────────────── Form checkpoints ─────────────────────────

    fun addFormCheckpoint(date: String, formId: String, moveIndex: Int, notes: String = "", autoExport: Boolean = true) {
        writableDb().insertOrThrow(
            "martial_arts_form_checkpoints",
            null,
            ContentValues().apply {
                put("date", date)
                put("form_id", formId)
                put("move_index", moveIndex)
                put("notes", notes.takeIf { it.isNotBlank() })
            },
        )
        if (autoExport) autoExportDatabaseIfConfigured()
    }

    fun getFormCheckpoints(formId: String, limit: Int = 12): List<FormCheckpoint> {
        val checkpoints = mutableListOf<FormCheckpoint>()
        readableDb().rawQuery(
            """
            SELECT id, date, form_id, move_index, COALESCE(notes, '') AS notes
            FROM martial_arts_form_checkpoints
            WHERE form_id = ?
            ORDER BY date DESC, id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(formId, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                checkpoints += FormCheckpoint(
                    id = cursor.long("id"),
                    date = cursor.string("date"),
                    formId = cursor.string("form_id"),
                    moveIndex = cursor.int("move_index"),
                    notes = cursor.string("notes"),
                )
            }
        }
        return checkpoints
    }

    // ───────────────────────── Quick logging ─────────────────────────

    /**
     * Log a standalone run straight into history — no draft session
     * ceremony. One lifts_new row in the same shape completed running
     * sessions produce, so every KPI picks it up unchanged.
     */
    fun logQuickRun(
        date: String,
        title: String,
        distanceKm: Double?,
        durationSeconds: Int?,
        paceSecondsPerKm: Double?,
        notes: String = "",
    ) {
        val rowTitle = title.ifBlank { "Run" }
        writableDb().insertOrThrow(
            "lifts_new",
            null,
            ContentValues().apply {
                put("Date", date)
                put("Exercise", rowTitle)
                put("Category", "Running")
                put("Workout", rowTitle)
                put("DurationSeconds", durationSeconds)
                put("DistanceKm", distanceKm)
                put("PaceSecondsPerKm", paceSecondsPerKm)
                put("Notes", notes.takeIf { it.isNotBlank() })
            },
        )
        invalidateDashboardKpiCache()
        autoExportDatabaseIfConfigured()
    }

    /**
     * Log a standalone martial-arts session (tai chi in the park, a few bag
     * rounds). Writes one history row with the Discipline column actually
     * filled in, and optionally records a form checkpoint ("got up to move N")
     * when the session was form practice.
     */
    fun logQuickMartialArts(
        date: String,
        discipline: String,
        exerciseName: String,
        durationSeconds: Int?,
        notes: String = "",
        formId: String = "",
        reachedMoveIndex: Int? = null,
    ) {
        val rowTitle = exerciseName.ifBlank { discipline.ifBlank { "Martial Arts" } }
        val checkpointNote = if (formId.isNotBlank() && reachedMoveIndex != null) {
            val form = getMartialArtsFormById(formId)
            val move = form?.moves?.firstOrNull { it.index == reachedMoveIndex }
            listOfNotNull("Reached ${reachedMoveIndex}/${form?.moveCount ?: "?"}", move?.name).joinToString(" ")
        } else {
            ""
        }
        // Row + checkpoint land or fail together, and the snapshot goes down the
        // river ONCE — this used to run two exports per quick log (halving the
        // effective backup retention) with no transaction between the writes.
        val database = writableDb()
        database.beginTransaction()
        try {
            database.insertOrThrow(
                "lifts_new",
                null,
                ContentValues().apply {
                    put("Date", date)
                    put("Exercise", rowTitle)
                    put("Category", "Martial Arts")
                    put("Discipline", discipline.takeIf { it.isNotBlank() })
                    put("Workout", rowTitle)
                    put("DurationSeconds", durationSeconds)
                    val joined = listOf(notes, checkpointNote).filter { it.isNotBlank() }.joinToString(" | ")
                    put("Notes", joined.takeIf { it.isNotBlank() })
                },
            )
            if (formId.isNotBlank() && reachedMoveIndex != null) {
                addFormCheckpoint(date, formId, reachedMoveIndex, notes, autoExport = false)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        invalidateDashboardKpiCache()
        autoExportDatabaseIfConfigured()
    }

        // ───────────────────────── Vocabularies ─────────────────────────

    /** Canonical + observed categories for dropdowns. */
    fun getCategories(): List<String> {
        val observed = mutableListOf<String>()
        readableDb().rawQuery(
            """
            SELECT DISTINCT TRIM(default_category) AS value FROM exercises WHERE TRIM(COALESCE(default_category, '')) != ''
            UNION
            SELECT DISTINCT TRIM(Category) AS value FROM lifts_new WHERE TRIM(COALESCE(Category, '')) != ''
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) observed += cursor.string("value")
        }
        return WorkoutVocab.merge(WorkoutVocab.categories, observed)
    }

    /** Canonical + catalogue + observed disciplines for dropdowns. */
    fun getDisciplines(): List<String> {
        val observed = mutableListOf<String>()
        readableDb().rawQuery(
            """
            SELECT DISTINCT name AS value FROM martial_arts_disciplines
            UNION
            SELECT DISTINCT TRIM(discipline) AS value FROM combos WHERE TRIM(COALESCE(discipline, '')) != ''
            UNION
            SELECT DISTINCT TRIM(Discipline) AS value FROM lifts_new WHERE TRIM(COALESCE(Discipline, '')) != ''
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) observed += cursor.string("value")
        }
        return WorkoutVocab.merge(WorkoutVocab.disciplines, observed)
    }

    private fun latestSessionDefaults(): SessionDefaults {
        readableDb().rawQuery(
            """
            SELECT MYWEIGHT, COALESCE(Diet, '') AS Diet
            FROM lifts_new
            WHERE MYWEIGHT IS NOT NULL
               OR TRIM(COALESCE(Diet, '')) != ''
            ORDER BY Date DESC, id DESC
            LIMIT 1
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return SessionDefaults()
            return SessionDefaults(
                bodyWeightKg = cursor.doubleOrNull("MYWEIGHT"),
                diet = cursor.string("Diet").trim(),
            )
        }
    }

    private suspend fun getDashboardKpis(): DashboardKpis {
        // Fast path: serve the cached snapshot when it's still valid.
        cachedDashboardKpis?.let { return it }
        // Compute under no lock — multiple racing computers will all produce equivalent
        // results and the last writer wins. The cost of a duplicate computation is far
        // less than the cost of holding a lock across ~15-20 queries.
        //
        // Cancellation checkpoints between sub-aggregations let the user navigate away
        // without forcing the whole batch to complete. Each ensureActive() call throws
        // CancellationException if the surrounding coroutine has been cancelled, which
        // the ViewModel's load() catch will see and quietly drop.
        val context = currentCoroutineContext()
        val attendance = getAttendanceKpis(); context.ensureActive()
        val running = getRunningKpis(); context.ensureActive()
        val martialArts = getMartialArtsKpis(); context.ensureActive()
        val featuredLifts = getDashboardFeaturedExercises().map { name ->
            context.ensureActive()
            getLiftMaxKpi(name)
        }
        val musclesHit = getMuscleHitKpis(); context.ensureActive()
        val categorySplit30 = getCategorySplitKpis(daysBack = 30); context.ensureActive()
        val categorySplitAll = getCategorySplitKpis(daysBack = null); context.ensureActive()
        val bodyWeight = getBodyWeightHistory(); context.ensureActive()
        val recentPrs = getRecentPrs(); context.ensureActive()
        val monthlyDays = getMonthlyTrainingDays(); context.ensureActive()
        val monthlyWeight = getMonthlyBodyweight(); context.ensureActive()
        val muscleMonths = getMuscleMonthMatrix(); context.ensureActive()
        val today = getTodayWorkoutSummary()
        val computed = DashboardKpis(
            attendance = attendance,
            running = running,
            martialArts = martialArts,
            featuredLifts = featuredLifts,
            musclesHit = musclesHit,
            categorySplit30Days = categorySplit30,
            categorySplitAllTime = categorySplitAll,
            bodyWeightHistory = bodyWeight,
            todayWorkout = today,
            recentPrs = recentPrs,
            monthlyTrainingDays = monthlyDays,
            monthlyBodyweight = monthlyWeight,
            muscleMonths = muscleMonths,
        )
        cachedDashboardKpis = computed
        return computed
    }

    /**
     * New all-time e1RM records set in the last [windowDays] days, across the
     * most-trained lifts. A session only counts as a PR if there was prior
     * history to beat — the first time an exercise is logged is a baseline,
     * not a record.
     */
    private fun getRecentPrs(windowDays: Long = 35, limit: Int = 8): List<PrRecord> {
        val cutoff = LocalDate.now().minusDays(windowDays).format(DATE_FORMAT)
        val candidates = mutableListOf<String>()
        // Candidates = every lift with a qualifying row in the PR window itself.
        // The old top-20-by-usage pool silently hid a real PR on the 21st lift.
        readableDb().rawQuery(
            """
            SELECT DISTINCT Exercise
            FROM lifts_new
            WHERE Date >= ?
              AND Weight IS NOT NULL AND Weight > 0
              AND Reps IS NOT NULL
              AND Exercise NOT LIKE '%:%'
            """.trimIndent(),
            arrayOf(cutoff),
        ).use { cursor ->
            while (cursor.moveToNext()) candidates += cursor.string("Exercise")
        }
        val prs = mutableListOf<PrRecord>()
        candidates.forEach { name ->
            // Full history, not a 200-session window — a capped baseline could
            // crown a "PR" an older session already beat. The dashboard KPI
            // cache bounds how often this scan actually runs.
            val sessionsOldestFirst = getLiftHistory(name, limit = Int.MAX_VALUE).sessions.asReversed()
            var bestSoFar = 0.0
            sessionsOldestFirst.forEach { session ->
                val e1rm = session.bestE1rm ?: return@forEach
                if (bestSoFar > 0 && e1rm > bestSoFar * 1.005 && session.date >= cutoff) {
                    val bestSet = session.sets.maxByOrNull { epleyE1rm(it.weight, it.reps) ?: 0.0 }
                    prs += PrRecord(
                        date = session.date,
                        exerciseName = name,
                        weight = bestSet?.weight,
                        reps = bestSet?.reps,
                        e1rm = e1rm,
                    )
                }
                if (e1rm > bestSoFar) bestSoFar = e1rm
            }
        }
        // Timed holds PR too: the longest hold ever, set inside the window.
        val holdFilter = """
            DurationSeconds IS NOT NULL AND Weight IS NULL
            AND LOWER(COALESCE(Category, '')) NOT IN ('running', 'martial arts')
            AND Exercise NOT LIKE '%:%'
        """.trimIndent()
        val holdCandidates = mutableListOf<String>()
        readableDb().rawQuery(
            "SELECT DISTINCT Exercise FROM lifts_new WHERE Date >= ? AND $holdFilter",
            arrayOf(cutoff),
        ).use { cursor -> while (cursor.moveToNext()) holdCandidates += cursor.string("Exercise") }
        holdCandidates.forEach { name ->
            val prior = readableDb().rawQuery(
                "SELECT MAX(DurationSeconds) AS best FROM lifts_new WHERE Exercise = ? COLLATE NOCASE AND Date < ? AND $holdFilter",
                arrayOf(name, cutoff),
            ).use { if (it.moveToFirst()) it.intOrNull("best") else null }
            if (prior == null || prior <= 0) return@forEach // first logs are baselines, not records
            readableDb().rawQuery(
                "SELECT Date, DurationSeconds FROM lifts_new WHERE Exercise = ? COLLATE NOCASE AND Date >= ? AND $holdFilter ORDER BY DurationSeconds DESC, Date DESC LIMIT 1",
                arrayOf(name, cutoff),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val best = cursor.intOrNull("DurationSeconds") ?: return@use
                    if (best > prior) {
                        prs += PrRecord(
                            date = cursor.string("Date"),
                            exerciseName = name,
                            durationSeconds = best,
                        )
                    }
                }
            }
        }
        return prs.sortedByDescending { it.date }.take(limit)
    }

    /** Distinct training days per (year, month) across all history — one query. */
    private fun getMonthlyTrainingDays(): List<MonthValue> {
        val values = mutableListOf<MonthValue>()
        readableDb().rawQuery(
            """
            SELECT substr(Date, 1, 4) AS y, substr(Date, 6, 2) AS m, COUNT(DISTINCT Date) AS days
            FROM lifts_new
            WHERE length(Date) >= 7
            GROUP BY y, m
            ORDER BY y, m
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val year = cursor.string("y").toIntOrNull() ?: continue
                val month = cursor.string("m").toIntOrNull() ?: continue
                values += MonthValue(year = year, month = month, value = cursor.int("days").toDouble())
            }
        }
        return values
    }

    /** Average logged body weight per (year, month) — one query. */
    private fun getMonthlyBodyweight(): List<MonthValue> {
        val values = mutableListOf<MonthValue>()
        readableDb().rawQuery(
            """
            SELECT substr(Date, 1, 4) AS y, substr(Date, 6, 2) AS m, AVG(day_weight) AS avg_weight
            FROM (
                -- One vote per DAY: MYWEIGHT is stamped on every set row, so a
                -- 30-set day used to outvote a quick-log day thirty-to-one.
                SELECT Date, MAX(MYWEIGHT) AS day_weight
                FROM lifts_new
                WHERE MYWEIGHT IS NOT NULL AND MYWEIGHT > 0 AND length(Date) >= 7
                GROUP BY Date
            )
            GROUP BY y, m
            ORDER BY y, m
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val year = cursor.string("y").toIntOrNull() ?: continue
                val month = cursor.string("m").toIntOrNull() ?: continue
                val weight = cursor.doubleOrNull("avg_weight") ?: continue
                values += MonthValue(year = year, month = month, value = weight)
            }
        }
        return values
    }

    /** Training roll-up for one calendar month, shown under the month header. */
    fun getMonthStats(month: YearMonth): MonthStats {
        val start = month.atDay(1).format(DATE_FORMAT)
        val end = month.atEndOfMonth().format(DATE_FORMAT)
        readableDb().rawQuery(
            """
            SELECT COUNT(DISTINCT Date) AS days,
                   COALESCE(SUM(COALESCE(DistanceKm, 0)), 0) AS km,
                   COALESCE(SUM(CASE WHEN LOWER(COALESCE(Category, '')) = 'martial arts' THEN COALESCE(DurationSeconds, 0) ELSE 0 END), 0) AS ma_seconds,
                   SUM(CASE WHEN (Weight IS NOT NULL AND Reps IS NOT NULL)
                              OR (DurationSeconds IS NOT NULL
                                  AND LOWER(COALESCE(Category, '')) NOT IN ('running', 'martial arts'))
                            THEN 1 ELSE 0 END) AS lift_sets
            FROM lifts_new
            WHERE Date >= ? AND Date <= ?
            """.trimIndent(),
            arrayOf(start, end),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return MonthStats()
            return MonthStats(
                trainingDays = cursor.int("days"),
                liftSets = cursor.intOrNull("lift_sets") ?: 0,
                runKm = cursor.doubleOrNull("km") ?: 0.0,
                martialArtsMinutes = ((cursor.longOrNull("ma_seconds") ?: 0L) / 60L).toInt(),
            )
        }
    }

    private fun getAttendanceKpis(): AttendanceKpis {
        val today = LocalDate.now()
        val start60 = today.minusDays(59)
        val thisYearStart = LocalDate.of(today.year, 1, 1)
        val lastYear = today.year - 1
        val lastYearStart = LocalDate.of(lastYear, 1, 1)
        val lastYearEnd = LocalDate.of(lastYear, 12, 31)
        // Feb 29 in a leap year has no counterpart last year — fall back to
        // Feb 28, not Dec 31 (which silently compared a WHOLE previous year).
        val lastYearToDateEnd = runCatching { today.withYear(lastYear) }
            .getOrElse { LocalDate.of(lastYear, 2, 28) }

        // Previously this method pulled every distinct workout date into memory and
        // counted the matches client-side — fine for a few hundred rows, wasteful
        // on a multi-year database. SQLite is happy to count for us; date strings
        // are ISO-formatted so lexicographic comparison matches chronological order.
        val today_s = today.format(DATE_FORMAT)
        val start60_s = start60.format(DATE_FORMAT)
        val thisYearStart_s = thisYearStart.format(DATE_FORMAT)
        val lastYearStart_s = lastYearStart.format(DATE_FORMAT)
        val lastYearEnd_s = lastYearEnd.format(DATE_FORMAT)
        val lastYearToDateEnd_s = lastYearToDateEnd.format(DATE_FORMAT)
        val sql = """
            SELECT
              COUNT(DISTINCT CASE WHEN Date BETWEEN ? AND ? THEN Date END) AS last60,
              COUNT(DISTINCT CASE WHEN Date BETWEEN ? AND ? THEN Date END) AS thisYear,
              COUNT(DISTINCT CASE WHEN Date BETWEEN ? AND ? THEN Date END) AS lastYearTotal,
              COUNT(DISTINCT CASE WHEN Date BETWEEN ? AND ? THEN Date END) AS lastYearToDate
            FROM lifts_new
            WHERE TRIM(COALESCE(Date, '')) != ''
        """.trimIndent()
        val args = arrayOf(
            start60_s, today_s,
            thisYearStart_s, today_s,
            lastYearStart_s, lastYearEnd_s,
            lastYearStart_s, lastYearToDateEnd_s,
        )
        val baseKpis = readableDb().rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) {
                AttendanceKpis()
            } else {
                AttendanceKpis(
                    last60Days = cursor.int("last60"),
                    thisYear = cursor.int("thisYear"),
                    lastYearTotal = cursor.int("lastYearTotal"),
                    lastYearToDate = cursor.int("lastYearToDate"),
                )
            }
        }

        // Per-day session counts for the dashboard heatmap and sparkline.
        // Counted as DISTINCT logged workouts per day (ignoring set-level rows
        // sharing the same Workout grouping where present, falling back to
        // raw row count when Workout is blank). Window is the last 53 weeks
        // so the heatmap can show a complete weekly grid.
        val heatmapStart = today.minusWeeks(53).format(DATE_FORMAT)
        val daily = mutableMapOf<String, Int>()
        val activity = mutableMapOf<String, DayActivity>()
        readableDb().rawQuery(
            """
            SELECT Date, COUNT(*) AS rowCount,
                   COUNT(DISTINCT CASE WHEN TRIM(COALESCE(Workout, '')) <> '' THEN TRIM(Workout) END)
                     + MAX(CASE WHEN TRIM(COALESCE(Workout, '')) = '' THEN 1 ELSE 0 END) AS sessionCount,
                   MAX(CASE WHEN Weight IS NOT NULL AND Reps IS NOT NULL THEN 1 ELSE 0 END) AS hasLift,
                   MAX(CASE WHEN LOWER(COALESCE(Category, '')) = 'running' THEN 1 ELSE 0 END) AS hasRun,
                   MAX(CASE WHEN LOWER(COALESCE(Category, '')) = 'martial arts' THEN 1 ELSE 0 END) AS hasMa
            FROM lifts_new
            WHERE Date BETWEEN ? AND ?
            GROUP BY Date
            """.trimIndent(),
            arrayOf(heatmapStart, today_s),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val date = cursor.string("Date")
                if (date.isNotBlank()) {
                    val rowCount = cursor.int("rowCount")
                    // dailyHistory's contract is WORKOUTS per day — the raw row
                    // count made a 4-workout week scrub as "80 sessions".
                    daily[date] = cursor.int("sessionCount")
                    activity[date] = DayActivity(
                        sets = rowCount,
                        hasLift = cursor.int("hasLift") == 1,
                        hasRun = cursor.int("hasRun") == 1,
                        hasMartialArts = cursor.int("hasMa") == 1,
                    )
                }
            }
        }
        return baseKpis.copy(dailyHistory = daily, dailyActivity = activity)
    }

    private fun getRunningKpis(): RunningKpis {
        val today = LocalDate.now()
        val start30 = today.minusDays(29).format(DATE_FORMAT)
        var distance30 = 0.0
        var thisYearDistance = 0.0
        var longestRun = 0.0
        var fastestPace: Double? = null
        val runDatesThisYear = mutableSetOf<String>()
        var runCount = 0
        val distanceByDate = linkedMapOf<String, Double>()
        val runRecords = mutableListOf<RunRecord>()

        readableDb().rawQuery(
            """
            SELECT Date, DistanceKm, PaceSecondsPerKm, DurationSeconds, COALESCE(Category, '') AS Category
            FROM lifts_new
            WHERE LOWER(TRIM(COALESCE(Category, ''))) = 'running'
               OR DistanceKm IS NOT NULL
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dateText = cursor.string("Date")
                val date = runCatching { LocalDate.parse(dateText, DATE_FORMAT) }.getOrNull()
                val distance = cursor.doubleOrNull("DistanceKm") ?: 0.0
                // A zero stored pace (or zero-duration row) must not crown the
                // all-time fastest — same guard the goal side got in #33.
                val pace = (
                    cursor.doubleOrNull("PaceSecondsPerKm")
                        ?: cursor.intOrNull("DurationSeconds")?.takeIf { distance > 0.0 }?.div(distance)
                    )?.takeIf { it > 0.0 }

                if (date != null && date.year == today.year) {
                    runDatesThisYear += dateText
                    thisYearDistance += distance
                }
                if (dateText >= start30) {
                    distance30 += distance
                }
                if (distance > 0.0) {
                    runCount += 1
                    distanceByDate[dateText] = (distanceByDate[dateText] ?: 0.0) + distance
                    runRecords += RunRecord(
                        date = dateText,
                        distanceKm = distance,
                        durationSeconds = cursor.intOrNull("DurationSeconds"),
                        paceSecondsPerKm = pace,
                    )
                }
                if (distance > longestRun) longestRun = distance
                if (pace != null && (fastestPace == null || pace < fastestPace!!)) {
                    fastestPace = pace
                }
            }
        }

        return RunningKpis(
            last30DaysDistanceKm = distance30,
            thisYearRuns = runDatesThisYear.size,
            thisYearDistanceKm = thisYearDistance,
            longestRunKm = longestRun,
            fastestPaceSecondsPerKm = fastestPace,
            averageRunKm = if (runCount == 0) 0.0 else distanceByDate.values.sum() / runCount,
            recentDistanceHistory = distanceByDate.entries.sortedBy { it.key }.map {
                ProgressPoint(date = it.key, value = it.value)
            },
            lastThreeLongRuns = runRecords.sortedWith(compareByDescending<RunRecord> { it.distanceKm }.thenByDescending { it.date }).take(3),
        )
    }

    private fun getMartialArtsKpis(): MartialArtsKpis {
        val rows = mutableListOf<MartialArtsRow>()
        readableDb().rawQuery(
            """
            SELECT Date, DurationSeconds, COALESCE(Exercise, '') AS Exercise
            FROM lifts_new
            WHERE LOWER(TRIM(COALESCE(Category, ''))) = 'martial arts'
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += MartialArtsRow(
                    date = cursor.string("Date"),
                    exercise = cursor.string("Exercise"),
                    durationSeconds = cursor.intOrNull("DurationSeconds"),
                )
            }
        }
        val thisYear = LocalDate.now().year.toString()
        return MartialArtsKpis(
            taiChi = buildMartialArtsDisciplineKpis("Tai Chi", rows) { row ->
                row.exercise.contains("tai chi", ignoreCase = true)
            },
            kickboxing = buildMartialArtsDisciplineKpis("Kickboxing", rows) { row ->
                row.exercise.contains("heavy bag", ignoreCase = true) ||
                    row.exercise.contains("shadow", ignoreCase = true) ||
                    row.exercise.contains("kickbox", ignoreCase = true) ||
                    row.exercise.contains("boxing", ignoreCase = true)
            },
            // Every martial row counts here — the two named breakdowns above
            // are highlights, not the definition of "martial arts".
            totalThisYearSessions = rows.filter { it.date.startsWith(thisYear) }.map { it.date }.distinct().size,
        )
    }

    private fun getLiftMaxKpi(exerciseName: String): LiftMaxKpi {
        val normalizedTarget = exerciseName.trim().lowercase(Locale.getDefault())
        var maxWeight: Double? = null
        var bestSetWeight: Double? = null
        var bestSetReps: Int? = null
        var bestVolume: Double? = null
        var lastAppearanceDate = ""
        val progressionByDate = linkedMapOf<String, Double>()
        readableDb().rawQuery(
            """
            SELECT Date, Weight, Reps, COALESCE(Exercise, '') AS Exercise
            FROM lifts_new
            WHERE Exercise = ? COLLATE NOCASE OR Exercise LIKE ? || ':%'
            """.trimIndent(),
            arrayOf(exerciseName.trim(), exerciseName.trim()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val loggedExercise = cursor.string("Exercise").substringBefore(':').trim().lowercase(Locale.getDefault())
                if (loggedExercise != normalizedTarget) continue
                val dateText = cursor.string("Date")
                val weight = cursor.doubleOrNull("Weight")
                val reps = parseLeadingInt(cursor.anyString("Reps"))
                if (weight != null && (maxWeight == null || weight > maxWeight!!)) {
                    maxWeight = weight
                }
                if (dateText.isNotBlank() && dateText > lastAppearanceDate) {
                    lastAppearanceDate = dateText
                }
                if (dateText.isNotBlank() && weight != null) {
                    val existing = progressionByDate[dateText]
                    if (existing == null || weight > existing) progressionByDate[dateText] = weight
                }
                if (weight != null && reps != null) {
                    val volume = weight * reps
                    if (bestVolume == null || volume > bestVolume!!) {
                        bestVolume = volume
                        bestSetWeight = weight
                        bestSetReps = reps
                    }
                }
            }
        }
        return LiftMaxKpi(
            exerciseName = exerciseName,
            maxWeight = maxWeight,
            maxSetWeight = bestSetWeight,
            maxSetReps = bestSetReps,
            maxSetVolume = bestVolume,
            lastAppearanceDate = lastAppearanceDate,
            progression = progressionByDate.entries.sortedBy { it.key }.map {
                ProgressPoint(date = it.key, value = it.value)
            },
        )
    }

    private fun buildMartialArtsDisciplineKpis(
        title: String,
        rows: List<MartialArtsRow>,
        include: (MartialArtsRow) -> Boolean,
    ): MartialArtsDisciplineKpis {
        val today = LocalDate.now()
        val start30 = today.minusDays(29)
        var totalSeconds30 = 0
        var totalSecondsThisYear = 0
        var longestRound: Int? = null
        val datesThisYear = mutableSetOf<String>()
        val labelCounts = linkedMapOf<String, Int>()
        val secondsByDate = linkedMapOf<String, Int>()
        rows.filter(include).forEach { row ->
            val date = runCatching { LocalDate.parse(row.date, DATE_FORMAT) }.getOrNull()
            val duration = row.durationSeconds
            if (date != null && date.year == today.year) {
                datesThisYear += row.date
                if (duration != null) totalSecondsThisYear += duration
            }
            if (date != null && !date.isBefore(start30) && duration != null) totalSeconds30 += duration
            if (duration != null && (longestRound == null || duration > longestRound!!)) longestRound = duration
            if (duration != null && row.date.isNotBlank()) {
                secondsByDate[row.date] = (secondsByDate[row.date] ?: 0) + duration
            }
            val label = row.exercise.substringAfter(':', "").ifBlank { row.exercise }.trim()
            if (label.isNotBlank()) labelCounts[label] = (labelCounts[label] ?: 0) + 1
        }
        return MartialArtsDisciplineKpis(
            title = title,
            last30DaysMinutes = totalSeconds30 / 60.0,
            thisYearSessions = datesThisYear.size,
            thisYearMinutes = totalSecondsThisYear / 60.0,
            longestRoundSeconds = longestRound,
            topLabel = labelCounts.maxByOrNull { it.value }?.key.orEmpty(),
            averageSessionMinutes = if (secondsByDate.isEmpty()) 0.0 else secondsByDate.values.average() / 60.0,
            recentMinutesHistory = secondsByDate.entries.sortedBy { it.key }.map {
                ProgressPoint(date = it.key, value = it.value / 60.0)
            },
        )
    }

    private fun getCategorySplitKpis(daysBack: Int?): List<CategorySplitKpi> {
        val items = linkedMapOf<String, Pair<Int, Int>>()
        val startDate = daysBack?.let { LocalDate.now().minusDays((it - 1).toLong()).format(DATE_FORMAT) }
        val sql = buildString {
            append(
                """
                SELECT
                    CASE
                        WHEN TRIM(COALESCE(l.Muscle, '')) != '' THEN TRIM(l.Muscle)
                        ELSE COALESCE(
                            (
                                SELECT NULLIF(TRIM(e.default_muscle), '')
                                FROM exercises e
                                WHERE LOWER(TRIM(e.name)) = LOWER(
                                    CASE
                                        WHEN instr(TRIM(COALESCE(l.Exercise, '')), ':') > 0
                                            THEN trim(substr(TRIM(COALESCE(l.Exercise, '')), 1, instr(TRIM(COALESCE(l.Exercise, '')), ':') - 1))
                                        ELSE TRIM(COALESCE(l.Exercise, ''))
                                    END
                                )
                                LIMIT 1
                            ),
                            ''
                        )
                    END AS muscle,
                    LOWER(TRIM(COALESCE(l.Category, ''))) AS category
                FROM lifts_new l
                """.trimIndent(),
            )
            if (startDate != null) append(" WHERE l.Date >= ?")
        }
        readableDb().rawQuery(sql, startDate?.let { arrayOf(it) }).use { cursor ->
            while (cursor.moveToNext()) {
                val muscle = cursor.string("muscle").trim()
                val category = cursor.string("category")
                if (muscle.isBlank()) continue
                val current = items[muscle] ?: (0 to 0)
                items[muscle] = when (category) {
                    "strength" -> (current.first + 1) to current.second
                    "hypertrophy" -> current.first to (current.second + 1)
                    else -> current
                }
            }
        }
        return items.map { (muscle, counts) ->
            CategorySplitKpi(muscle = muscle, strengthSets = counts.first, hypertrophySets = counts.second)
        }.filter { it.strengthSets > 0 || it.hypertrophySets > 0 }
            .sortedByDescending { it.strengthSets + it.hypertrophySets }
            .take(8)
    }

    /**
     * Distinct training days per (muscle, year, month) over the last 12 months
     * — the balance grid. Same derived-muscle resolution as getMuscleHitKpis
     * (explicit Muscle column first, else the exercise's default, with
     * "Parent: combo" titles resolved by their parent).
     */
    private fun getMuscleMonthMatrix(monthsBack: Long = 12): List<MuscleMonthValue> {
        val start = YearMonth.now().minusMonths(monthsBack - 1).atDay(1).format(DATE_FORMAT)
        val values = mutableListOf<MuscleMonthValue>()
        readableDb().rawQuery(
            """
            SELECT derived_muscle AS muscle, substr(Date, 1, 4) AS y, substr(Date, 6, 2) AS m,
                   COUNT(DISTINCT Date) AS days
            FROM (
                SELECT l.Date AS Date, CASE
                    WHEN TRIM(COALESCE(l.Muscle, '')) != '' THEN TRIM(l.Muscle)
                    ELSE COALESCE(
                        (
                            SELECT NULLIF(TRIM(e.default_muscle), '')
                            FROM exercises e
                            WHERE LOWER(TRIM(e.name)) = LOWER(
                                CASE
                                    WHEN instr(TRIM(COALESCE(l.Exercise, '')), ':') > 0
                                        THEN trim(substr(TRIM(COALESCE(l.Exercise, '')), 1, instr(TRIM(COALESCE(l.Exercise, '')), ':') - 1))
                                    ELSE TRIM(COALESCE(l.Exercise, ''))
                                END
                            )
                            LIMIT 1
                        ),
                        ''
                    )
                END AS derived_muscle
                FROM lifts_new l
                WHERE l.Date >= ? AND length(l.Date) >= 7
                  AND LOWER(COALESCE(l.Category, '')) NOT IN ('running', 'martial arts', 'mobility')
            )
            WHERE TRIM(COALESCE(derived_muscle, '')) != ''
            GROUP BY derived_muscle, y, m
            """.trimIndent(),
            arrayOf(start),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val year = cursor.string("y").toIntOrNull() ?: continue
                val month = cursor.string("m").toIntOrNull() ?: continue
                values += MuscleMonthValue(
                    muscle = cursor.string("muscle"),
                    year = year,
                    month = month,
                    days = cursor.int("days"),
                )
            }
        }
        return values
    }

    private fun getMuscleHitKpis(): List<MuscleHitKpi> {
        val start30 = LocalDate.now().minusDays(29).format(DATE_FORMAT)
        val items = mutableListOf<MuscleHitKpi>()
        readableDb().rawQuery(
            """
            SELECT derived_muscle AS muscle, COUNT(*) AS total_sets
            FROM (
                SELECT CASE
                    WHEN TRIM(COALESCE(l.Muscle, '')) != '' THEN TRIM(l.Muscle)
                    ELSE COALESCE(
                        (
                            SELECT NULLIF(TRIM(e.default_muscle), '')
                            FROM exercises e
                            WHERE LOWER(TRIM(e.name)) = LOWER(
                                CASE
                                    WHEN instr(TRIM(COALESCE(l.Exercise, '')), ':') > 0
                                        THEN trim(substr(TRIM(COALESCE(l.Exercise, '')), 1, instr(TRIM(COALESCE(l.Exercise, '')), ':') - 1))
                                    ELSE TRIM(COALESCE(l.Exercise, ''))
                                END
                            )
                            LIMIT 1
                        ),
                        ''
                    )
                END AS derived_muscle
                FROM lifts_new l
                WHERE l.Date >= ?
                  -- Strength work only: a mobility drill or a run is not a
                  -- "set" of a muscle for balance purposes (verified 30 Aug:
                  -- Legs read 77 of which 30 were armour-block mobility).
                  AND LOWER(COALESCE(l.Category, '')) NOT IN ('running', 'martial arts', 'mobility')
            )
            WHERE TRIM(COALESCE(derived_muscle, '')) != ''
            GROUP BY derived_muscle
            ORDER BY total_sets DESC, muscle ASC
            """.trimIndent(),
            arrayOf(start30),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += MuscleHitKpi(
                    muscle = cursor.string("muscle"),
                    sets = cursor.int("total_sets"),
                )
            }
        }
        return items
    }

    private fun getTodayWorkoutSummary(): TodayWorkoutSummary? {
        val today = LocalDate.now().format(DATE_FORMAT)
        val draft = getDraftSessions().firstOrNull { it.workoutDate == today }
        if (draft != null) {
            return TodayWorkoutSummary(
                title = draft.name.ifBlank { "Custom Workout" },
                subtitle = "${draft.items.size} exercises • draft",
                actionLabel = "Resume",
            )
        }
        val recommendation = getTodayPlanRecommendation(today) ?: return null
        val title = when (recommendation.dayType) {
            PlanDayType.TEMPLATE -> recommendation.templateName
            PlanDayType.CUSTOM -> recommendation.customWorkoutName
            PlanDayType.REST -> "Rest day"
        }.ifBlank { "Workout" }
        val subtitle = when (recommendation.dayType) {
            PlanDayType.REST -> recommendation.planName
            else -> listOf(recommendation.planName, "Today").filter { it.isNotBlank() }.joinToString(" • ")
        }
        return TodayWorkoutSummary(
            title = title,
            subtitle = subtitle,
            actionLabel = if (recommendation.dayType == PlanDayType.REST) "View" else "Open",
        )
    }

    private fun getBodyWeightHistory(): List<WeightPoint> {
        val items = mutableListOf<WeightPoint>()
        readableDb().rawQuery(
            """
            SELECT Date, MAX(COALESCE(MYWEIGHT, 0)) AS body_weight
            FROM lifts_new
            WHERE MYWEIGHT IS NOT NULL
            GROUP BY Date
            ORDER BY Date DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val weight = cursor.doubleOrNull("body_weight") ?: continue
                if (weight > 0.0) {
                    items += WeightPoint(
                        date = cursor.string("Date"),
                        weightKg = weight,
                    )
                }
            }
        }
        return items.reversed()
    }

    private fun resolveLoggedCategory(
        exercise: ExerciseCatalogItem?,
        set: WorkoutSessionSet,
        fallback: String?,
    ): String {
        val base = exercise?.defaultCategory?.trim().orEmpty()
        if (base.equals("Hypertrophy", true)) {
            val reps = parseLeadingInt(set.repsText)
            if (reps != null && reps <= 5) return "Strength"
        }
        return base.ifBlank { fallback.orEmpty() }
    }

    private fun searchExercises(query: String, limit: Int? = 30): List<ExerciseCatalogItem> {
        val args = mutableListOf<String>()
        val sql = buildString {
            append(
                """
                SELECT e.id, e.name, e.default_muscle, e.default_category, e.is_bodyweight, e.log_format, e.movement_type, e.default_role, e.weight_unit, e.per_side,
                       COALESCE(stats.usage_count, 0) AS usage_count
                FROM exercises e
                LEFT JOIN (
                    SELECT Exercise AS exercise_name, COUNT(*) AS usage_count
                    FROM lifts_new
                    WHERE Exercise IS NOT NULL AND TRIM(Exercise) != ''
                    GROUP BY Exercise
                ) stats ON stats.exercise_name = e.name
                """.trimIndent(),
            )
            if (query.isNotBlank()) {
                append(" WHERE e.name LIKE ?")
                args += "%${query.trim()}%"
            }
            append(" ORDER BY usage_count DESC, e.name ASC")
            if (limit != null) {
                append(" LIMIT ?")
                args += limit.toString()
            }
        }
        val items = mutableListOf<ExerciseCatalogItem>()
        readableDb().rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                items += ExerciseCatalogItem(
                    id = cursor.long("id"),
                    name = cursor.string("name"),
                    defaultMuscle = cursor.string("default_muscle"),
                    defaultCategory = cursor.string("default_category"),
                    isBodyweight = cursor.int("is_bodyweight") == 1,
                    logFormat = LogFormat.fromDb(cursor.string("log_format")),
                    movementType = MovementType.fromDb(cursor.string("movement_type")),
                    defaultRole = ProgressionRole.fromDb(cursor.string("default_role")),
                    weightUnit = WeightUnit.fromDb(cursor.string("weight_unit")),
                    perSide = cursor.intOrNull("per_side") == 1,
                    usageCount = cursor.int("usage_count"),
                )
            }
        }
        return items
    }

    private fun getAllExercises(): List<ExerciseCatalogItem> = searchExercises(query = "", limit = null)

    private fun getAllCombos(): List<ComboItem> {
        val items = mutableListOf<ComboItem>()
        readableDb().rawQuery(
            "SELECT id, name, discipline, COALESCE(notes, '') AS notes, COALESCE(moves_json, '') AS moves_json FROM combos ORDER BY discipline, name",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += ComboItem(
                    id = cursor.long("id"),
                    name = cursor.string("name"),
                    discipline = cursor.string("discipline"),
                    notes = cursor.string("notes"),
                    moves = parseComboMovesJson(cursor.string("moves_json")),
                )
            }
        }
        return items
    }

    /**
     * Decode the JSON array stored in `combos.moves_json` into the typed
     * [ComboMove] list. Tolerates blank input ("" / null), legacy combos that
     * never had a moves_json value, and unknown move ids (they're skipped
     * rather than throwing — the catalogue may shrink in a future release and
     * we shouldn't blow up on an old combo that referenced a removed move).
     */
    private fun parseComboMovesJson(raw: String): List<ComboMove> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> ComboMove.fromId(arr.optString(i)) }
        }.getOrElse {
            Log.w(TAG, "Failed to parse combo moves_json: $raw", it)
            emptyList()
        }
    }

    private fun encodeComboMovesJson(moves: List<ComboMove>): String =
        JSONArray().apply { moves.forEach { put(it.id) } }.toString()

    private fun getTemplates(database: SQLiteDatabase = readableDb()): List<WorkoutTemplate> {
        val templates = mutableListOf<WorkoutTemplate>()
        database.rawQuery(
            """
            SELECT id, name, COALESCE(discipline, '') AS discipline, COALESCE(notes, '') AS notes
            FROM workout_templates
            ORDER BY name
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.long("id")
                templates += WorkoutTemplate(
                    id = id,
                    name = cursor.string("name"),
                    discipline = cursor.string("discipline"),
                    notes = cursor.string("notes"),
                    items = loadTemplateItems(id, database),
                )
            }
        }
        return templates
    }

    private fun loadWorkoutPlanDays(planId: Long, database: SQLiteDatabase = readableDb()): List<WorkoutPlanDay> {
        val items = mutableListOf<WorkoutPlanDay>()
        database.rawQuery(
            """
            SELECT id, week_index, day_index, COALESCE(day_label, '') AS day_label,
                   COALESCE(day_type, 'rest') AS day_type, template_id,
                   COALESCE(template_name, '') AS template_name,
                   COALESCE(custom_workout_name, '') AS custom_workout_name
            FROM workout_plan_days
            WHERE plan_id = ?
            ORDER BY week_index, day_index, id
            """.trimIndent(),
            arrayOf(planId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += WorkoutPlanDay(
                    id = cursor.long("id"),
                    weekIndex = cursor.int("week_index"),
                    dayIndex = cursor.int("day_index"),
                    dayLabel = cursor.string("day_label"),
                    dayType = PlanDayType.fromDb(cursor.string("day_type")),
                    templateId = cursor.longOrNull("template_id"),
                    templateName = cursor.string("template_name"),
                    customWorkoutName = cursor.string("custom_workout_name"),
                )
            }
        }
        return items
    }

    private fun loadTemplateItems(templateId: Long, database: SQLiteDatabase = readableDb()): List<WorkoutTemplateItem> {
        val items = mutableListOf<WorkoutTemplateItem>()
        database.rawQuery(
            """
            SELECT id, sort_order, COALESCE(exercise_name, '') AS exercise_name, combo_id,
                   COALESCE(combo_name, '') AS combo_name, sets_target, COALESCE(reps_target, '') AS reps_target,
                   COALESCE(progression_role, 'accessory') AS progression_role, weight_target, rest_target_seconds, duration_target_seconds, distance_target_km,
                   pace_target_seconds_per_km, rpe_target, COALESCE(notes, '') AS notes, COALESCE(superset_id, '') AS superset_id
            FROM workout_template_items
            WHERE template_id = ?
            ORDER BY sort_order, id
            """.trimIndent(),
            arrayOf(templateId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += WorkoutTemplateItem(
                    id = cursor.long("id"),
                    sortOrder = cursor.int("sort_order"),
                    exerciseName = cursor.string("exercise_name"),
                    comboId = cursor.longOrNull("combo_id"),
                    comboName = cursor.string("combo_name"),
                    progressionRole = ProgressionRole.fromDb(cursor.string("progression_role")),
                    setsTarget = cursor.intOrNull("sets_target"),
                    repsTarget = cursor.string("reps_target"),
                    weightTarget = cursor.doubleOrNull("weight_target"),
                    restTargetSeconds = cursor.intOrNull("rest_target_seconds"),
                    durationTargetSeconds = cursor.intOrNull("duration_target_seconds"),
                    distanceTargetKm = cursor.doubleOrNull("distance_target_km"),
                    paceTargetSecondsPerKm = cursor.intOrNull("pace_target_seconds_per_km"),
                    rpeTarget = cursor.intOrNull("rpe_target"),
                    notes = cursor.string("notes"),
                    supersetId = cursor.string("superset_id"),
                    hints = if (cursor.string("exercise_name").isNotBlank()) getPerformanceHint(cursor.string("exercise_name")) else PerformanceHint(),
                )
            }
        }
        return items
    }

    private fun loadSessionItems(sessionId: Long): List<WorkoutSessionItem> {
        val items = mutableListOf<WorkoutSessionItem>()
        readableDb().rawQuery(
            """
            SELECT id, sort_order, COALESCE(exercise_name, '') AS exercise_name, combo_id,
                   COALESCE(combo_name, '') AS combo_name, planned_sets, COALESCE(target_reps, '') AS target_reps,
                   COALESCE(progression_role, 'accessory') AS progression_role, target_weight, target_rest_seconds, target_duration_seconds, target_distance_km,
                   target_pace_seconds_per_km, target_rpe, COALESCE(notes, '') AS notes, COALESCE(superset_id, '') AS superset_id,
                   COALESCE(coach_note, '') AS coach_note, COALESCE(pending_comment, '') AS pending_comment
            FROM workout_session_items
            WHERE session_id = ?
            ORDER BY sort_order, id
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val exerciseName = cursor.string("exercise_name")
                items += WorkoutSessionItem(
                    id = cursor.long("id"),
                    sortOrder = cursor.int("sort_order"),
                    exerciseName = exerciseName,
                    comboId = cursor.longOrNull("combo_id"),
                    comboName = cursor.string("combo_name"),
                    progressionRole = ProgressionRole.fromDb(cursor.string("progression_role")),
                    plannedSets = cursor.intOrNull("planned_sets"),
                    targetReps = cursor.string("target_reps"),
                    targetWeight = cursor.doubleOrNull("target_weight"),
                    targetRestSeconds = cursor.intOrNull("target_rest_seconds"),
                    targetDurationSeconds = cursor.intOrNull("target_duration_seconds"),
                    targetDistanceKm = cursor.doubleOrNull("target_distance_km"),
                    targetPaceSecondsPerKm = cursor.intOrNull("target_pace_seconds_per_km"),
                    targetRpe = cursor.intOrNull("target_rpe"),
                    notes = cursor.string("notes"),
                    supersetId = cursor.string("superset_id"),
                    coachNote = cursor.string("coach_note"),
                    hints = if (exerciseName.isNotBlank()) getPerformanceHint(exerciseName) else PerformanceHint(),
                    loggedSets = loadSessionSets(cursor.long("id")),
                    pendingComment = cursor.string("pending_comment"),
                )
            }
        }
        return items
    }

    private fun loadSessionSets(sessionItemId: Long): List<WorkoutSessionSet> {
        val sets = mutableListOf<WorkoutSessionSet>()
        readableDb().rawQuery(
            """
            SELECT id, set_index, weight, COALESCE(reps_text, '') AS reps_text, combo_id,
                   COALESCE(combo_name, '') AS combo_name, COALESCE(form_id, '') AS form_id,
                   form_move_index, rest_seconds, duration_seconds,
                   distance_km, pace_seconds_per_km, rpe, COALESCE(notes, '') AS notes,
                   COALESCE(comment, '') AS comment, logged_at_ms
            FROM workout_session_sets
            WHERE session_item_id = ?
            ORDER BY set_index, id
            """.trimIndent(),
            arrayOf(sessionItemId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sets += WorkoutSessionSet(
                    id = cursor.long("id"),
                    setIndex = cursor.int("set_index"),
                    weight = cursor.doubleOrNull("weight"),
                    repsText = cursor.string("reps_text"),
                    comboId = cursor.longOrNull("combo_id"),
                    comboName = cursor.string("combo_name"),
                    formId = cursor.string("form_id"),
                    formMoveIndex = cursor.intOrNull("form_move_index"),
                    restSeconds = cursor.intOrNull("rest_seconds"),
                    durationSeconds = cursor.intOrNull("duration_seconds"),
                    distanceKm = cursor.doubleOrNull("distance_km"),
                    paceSecondsPerKm = cursor.doubleOrNull("pace_seconds_per_km"),
                    rpe = cursor.intOrNull("rpe"),
                    notes = cursor.string("notes"),
                    comment = cursor.string("comment"),
                    loggedAtMillis = cursor.longOrNull("logged_at_ms"),
                )
            }
        }
        return sets
    }

    private fun getRecentWorkoutGroups(limit: Int): List<WorkoutGroup> {
        val groups = mutableListOf<WorkoutGroup>()
        readableDb().rawQuery(
            """
            SELECT Date, MIN(TRIM(COALESCE(Workout, ''))) AS workout_name
            FROM lifts_new
            GROUP BY Date, CASE
                WHEN TRIM(COALESCE(Workout, '')) = '' THEN Date
                ELSE TRIM(Workout)
            END
            ORDER BY Date DESC, MAX(id) DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val date = cursor.string("Date")
                val workoutName = cursor.string("workout_name")
                groups += loadWorkoutGroup(date, workoutName)
            }
        }
        return groups
    }

    private fun getWorkoutGroupsForDate(date: String): List<WorkoutGroup> {
        val groups = mutableListOf<WorkoutGroup>()
        readableDb().rawQuery(
            """
            SELECT MIN(TRIM(COALESCE(Workout, ''))) AS workout_name
            FROM lifts_new
            WHERE Date = ?
            GROUP BY CASE
                WHEN TRIM(COALESCE(Workout, '')) = '' THEN Date
                ELSE TRIM(Workout)
            END
            ORDER BY MAX(id) DESC
            """.trimIndent(),
            arrayOf(date),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                groups += loadWorkoutGroup(date, cursor.string("workout_name"))
            }
        }
        return groups
    }

    /**
     * Remove one completed workout from history: its `lifts_new` rows (same
     * selection as [loadWorkoutGroup], so what you saw is what leaves) and any
     * sealed session row that produced them, so nothing lingers or re-flushes.
     * Deliberate and irreversible — the UI double-gates it.
     */
    fun deleteWorkoutGroup(date: String, workoutName: String) {
        val database = writableDb()
        database.beginTransaction()
        try {
            // Form checkpoints written by this workout would otherwise outlive
            // it — the Hall kept reporting "practised to move N" from an erased
            // session. Map the doomed rows' "Parent: Form" titles back to form
            // ids and take those checkpoints (same date) with the group.
            val groupWhere = if (workoutName.isBlank()) {
                "Date = ? AND TRIM(COALESCE(Workout, '')) = ''" to arrayOf(date)
            } else {
                "Date = ? AND TRIM(COALESCE(Workout, '')) = ?" to arrayOf(date, workoutName)
            }
            val formTitles = mutableSetOf<String>()
            database.rawQuery(
                "SELECT DISTINCT Exercise FROM lifts_new WHERE ${groupWhere.first} AND Exercise LIKE '%:%'",
                groupWhere.second,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.substringAfterLast(':')?.trim()
                        ?.takeIf { it.isNotBlank() }?.let { formTitles += it }
                }
            }
            if (formTitles.isNotEmpty()) {
                val placeholders = formTitles.joinToString(",") { "?" }
                database.execSQL(
                    """
                    DELETE FROM martial_arts_form_checkpoints
                    WHERE date = ? AND form_id IN (
                        SELECT id FROM martial_arts_forms
                        WHERE TRIM(short_title) IN ($placeholders) OR TRIM(title) IN ($placeholders)
                    )
                    """.trimIndent(),
                    arrayOf(date) + formTitles.toTypedArray() + formTitles.toTypedArray(),
                )
            }
            if (workoutName.isBlank()) {
                database.delete("lifts_new", "Date = ? AND TRIM(COALESCE(Workout, '')) = ''", arrayOf(date))
            } else {
                database.delete("lifts_new", "Date = ? AND TRIM(COALESCE(Workout, '')) = ?", arrayOf(date, workoutName))
            }
            if (workoutName.isNotBlank()) {
                val sessionIds = mutableListOf<Long>()
                database.rawQuery(
                    "SELECT id FROM workout_sessions WHERE workout_date = ? AND name = ? AND status = ?",
                    arrayOf(date, workoutName, SessionStatus.COMPLETED.dbValue),
                ).use { cursor ->
                    while (cursor.moveToNext()) sessionIds += cursor.long("id")
                }
                sessionIds.forEach { id ->
                    database.delete(
                        "workout_session_sets",
                        "session_item_id IN (SELECT id FROM workout_session_items WHERE session_id = ?)",
                        arrayOf(id.toString()),
                    )
                    database.delete("workout_session_items", "session_id = ?", arrayOf(id.toString()))
                    database.delete("workout_sessions", "id = ?", arrayOf(id.toString()))
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        invalidateDashboardKpiCache()
        autoExportDatabaseIfConfigured()
    }

    private fun loadWorkoutGroup(date: String, workoutName: String): WorkoutGroup {
        val args = mutableListOf(date)
        val selection = if (workoutName.isBlank()) {
            "Date = ? AND TRIM(COALESCE(Workout, '')) = ''"
        } else {
            args += workoutName
            "Date = ? AND TRIM(COALESCE(Workout, '')) = ?"
        }

        val orderedEntries = linkedMapOf<String, MutableList<WorkoutSetRecord>>()
        val metadata = linkedMapOf<String, Triple<String, Long?, Pair<String, String>>>()
        var minLogged = Long.MAX_VALUE
        var maxLogged = Long.MIN_VALUE
        readableDb().rawQuery(
            """
            SELECT id, Exercise, COALESCE(Category, '') AS Category, COALESCE(Discipline, '') AS Discipline,
                   ComboID, COALESCE(set_type, '') AS set_type, Weight, Reps, Rest, DurationSeconds,
                   DistanceKm, PaceSecondsPerKm, RPE, COALESCE(Notes, '') AS Notes, LoggedAt
            FROM lifts_new
            WHERE $selection
            ORDER BY id ASC
            """.trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.longOrNull("LoggedAt")?.let { if (it < minLogged) minLogged = it; if (it > maxLogged) maxLogged = it }
                val title = cursor.string("Exercise")
                val comboId = cursor.longOrNull("ComboID")
                val setType = cursor.string("set_type")
                val category = cursor.string("Category")
                val discipline = cursor.string("Discipline")
                val key = listOf(title, comboId?.toString().orEmpty(), setType, category, discipline).joinToString("|")
                metadata[key] = Triple(title, comboId, category to discipline)
                orderedEntries.getOrPut(key) { mutableListOf() } += WorkoutSetRecord(
                    id = cursor.long("id"),
                    weight = cursor.doubleOrNull("Weight"),
                    repsText = cursor.anyString("Reps"),
                    restSeconds = cursor.intOrNull("Rest"),
                    durationSeconds = cursor.intOrNull("DurationSeconds"),
                    distanceKm = cursor.doubleOrNull("DistanceKm"),
                    paceSecondsPerKm = cursor.doubleOrNull("PaceSecondsPerKm"),
                    rpe = cursor.intOrNull("RPE"),
                    notes = cursor.string("Notes"),
                    loggedAtMillis = cursor.longOrNull("LoggedAt"),
                )
            }
        }
        val durationSeconds = if (maxLogged > minLogged) ((maxLogged - minLogged) / 1000L).toInt() else null
        return WorkoutGroup(
            date = date,
            workoutName = workoutName,
            entries = orderedEntries.map { (key, sets) ->
                val entryInfo = metadata.getValue(key)
                WorkoutEntry(
                    title = entryInfo.first,
                    comboId = entryInfo.second,
                    category = entryInfo.third.first,
                    discipline = entryInfo.third.second,
                    sets = sets,
                )
            },
            durationSeconds = durationSeconds,
        )
    }

    private fun getExerciseHistorySummary(exerciseName: String): ExerciseHistorySummary {
        val logFormat = getExerciseByName(exerciseName)?.logFormat ?: LogFormat.REPS
        val hint = getPerformanceHint(exerciseName, logFormat)
        val workouts = getRecentWorkoutGroupsForExercise(exerciseName, 6)
        var totalSets = 0
        var workoutDates = mutableSetOf<String>()
        var lastPerformedDate = ""
        var bestWeight: Double? = null
        var bestVolume: Double? = null
        var bestDuration: Int? = null
        val durationByDate = sortedMapOf<String, Int>()
        readableDb().rawQuery(
            """
            SELECT Date, Weight, Reps, DurationSeconds
            FROM lifts_new
            WHERE Exercise = ? COLLATE NOCASE
            ORDER BY Date DESC, id DESC
            """.trimIndent(),
            arrayOf(exerciseName),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                totalSets += 1
                val date = cursor.string("Date")
                if (lastPerformedDate.isBlank()) lastPerformedDate = date
                workoutDates += date
                val weight = cursor.doubleOrNull("Weight")
                val reps = parseLeadingInt(cursor.anyString("Reps"))
                if (weight != null && (bestWeight == null || weight > bestWeight!!)) {
                    bestWeight = weight
                }
                if (weight != null && reps != null) {
                    val volume = weight * reps
                    if (bestVolume == null || volume > bestVolume!!) bestVolume = volume
                }
                cursor.intOrNull("DurationSeconds")?.let { dur ->
                    if (bestDuration == null || dur > bestDuration!!) bestDuration = dur
                    if (date.isNotBlank() && dur > (durationByDate[date] ?: 0)) durationByDate[date] = dur
                }
            }
        }
        return ExerciseHistorySummary(
            exerciseName = exerciseName,
            logFormat = logFormat,
            hint = hint,
            workoutCount = workoutDates.size,
            totalSets = totalSets,
            lastPerformedDate = lastPerformedDate,
            bestWeight = bestWeight,
            bestVolume = bestVolume,
            recentWorkouts = workouts,
            e1rmTrend = getLiftHistory(exerciseName, limit = 24).sessions
                .mapNotNull { s -> s.bestE1rm?.let { ProgressPoint(s.date, it) } }
                .asReversed(),
            bestDurationSeconds = bestDuration,
            durationTrend = durationByDate.entries.toList().takeLast(24)
                .map { (date, dur) -> ProgressPoint(date, dur.toDouble()) },
        )
    }

    private fun getRecentWorkoutGroupsForExercise(exerciseName: String, limit: Int): List<WorkoutGroup> {
        val groups = mutableListOf<WorkoutGroup>()
        readableDb().rawQuery(
            """
            SELECT Date, MIN(TRIM(COALESCE(Workout, ''))) AS workout_name
            FROM lifts_new
            WHERE Exercise = ? COLLATE NOCASE
            GROUP BY Date, CASE
                WHEN TRIM(COALESCE(Workout, '')) = '' THEN Date
                ELSE TRIM(Workout)
            END
            ORDER BY Date DESC, MAX(id) DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(exerciseName, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                groups += loadWorkoutGroup(cursor.string("Date"), cursor.string("workout_name"))
            }
        }
        return groups
    }

    private fun markerTypeForTemplateItem(item: WorkoutTemplateItem): CalendarMarkerType {
        val exercise = item.exerciseName.takeIf { it.isNotBlank() }?.let(::getExerciseByName)
        return markerTypeForCategory(exercise?.defaultCategory, item.exerciseName)
    }

    private fun markerTypeForSessionItem(item: WorkoutSessionItem): CalendarMarkerType {
        val exercise = item.exerciseName.takeIf { it.isNotBlank() }?.let(::getExerciseByName)
        return markerTypeForCategory(exercise?.defaultCategory, item.exerciseName)
    }

    private fun markerTypeForCategory(category: String?, title: String?): CalendarMarkerType {
        val normalized = category?.trim()?.lowercase().orEmpty()
        val titleText = title?.trim()?.lowercase().orEmpty()
        return when {
            normalized.contains("martial") || titleText in listOf("heavy bag", "shadowboxing", "speed bag") -> CalendarMarkerType.MARTIAL_ARTS
            normalized.contains("run") -> CalendarMarkerType.RUNNING
            normalized.contains("mobility") || normalized.contains("stretch") || titleText.contains("stretch") || titleText.contains("foam roll") -> CalendarMarkerType.MOBILITY
            normalized.contains("hypertrophy") || normalized.contains("bodybuilding") -> CalendarMarkerType.HYPERTROPHY
            else -> CalendarMarkerType.STRENGTH
        }
    }

    // Hints scan an exercise's full history (the all-time best must stay honest),
    // so a 10-item session load used to cost 20+ unbounded queries. Cache per
    // exercise instead; any history mutation clears it via the KPI invalidation.
    private val cachedHints = java.util.concurrent.ConcurrentHashMap<String, PerformanceHint>()

    private fun getPerformanceHint(exerciseName: String, logFormat: LogFormat? = null): PerformanceHint {
        val cacheKey = exerciseName.lowercase() + "|" + (logFormat?.name ?: "*")
        cachedHints[cacheKey]?.let { return it }
        return computePerformanceHint(exerciseName, logFormat ?: getExerciseByName(exerciseName)?.logFormat ?: LogFormat.REPS)
            .also { cachedHints[cacheKey] = it }
    }

    private fun computePerformanceHint(exerciseName: String, logFormat: LogFormat): PerformanceHint {
        // Fetch all history rows for this exercise plus their date so we can
        // surface the *full last session* (every set the user did on that date)
        // rather than just one collapsed "last set" string. Ordered newest-first
        // by date, then by id within each date so set order on a single day is
        // preserved (id is monotonic per insertion).
        data class Row(val date: String, val record: WorkoutSetRecord)
        val rows = mutableListOf<Row>()
        readableDb().rawQuery(
            """
            SELECT id, COALESCE(Date, '') AS Date, Weight, Reps, Rest, DurationSeconds,
                   DistanceKm, PaceSecondsPerKm, RPE, COALESCE(Notes, '') AS Notes
            FROM lifts_new
            WHERE Exercise = ? COLLATE NOCASE
            ORDER BY Date DESC, id DESC
            """.trimIndent(),
            arrayOf(exerciseName),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    date = cursor.string("Date"),
                    record = WorkoutSetRecord(
                        id = cursor.long("id"),
                        weight = cursor.doubleOrNull("Weight"),
                        repsText = cursor.anyString("Reps"),
                        restSeconds = cursor.intOrNull("Rest"),
                        durationSeconds = cursor.intOrNull("DurationSeconds"),
                        distanceKm = cursor.doubleOrNull("DistanceKm"),
                        paceSecondsPerKm = cursor.doubleOrNull("PaceSecondsPerKm"),
                        rpe = cursor.intOrNull("RPE"),
                        notes = cursor.string("Notes"),
                    ),
                )
            }
        }

        // Most-recent session = every row sharing the most-recent non-blank
        // date. Within that group we reverse the id-desc order coming from the
        // query so the UI displays sets in the order they happened.
        val mostRecentDate = rows.firstOrNull { it.date.isNotBlank() }?.date.orEmpty()
        val lastSessionRows = if (mostRecentDate.isNotBlank()) {
            rows.filter { it.date == mostRecentDate }.map { it.record }.reversed()
        } else {
            emptyList()
        }

        val records = rows.map { it.record }
        val last = records.firstOrNull()?.let { describePerformance(it, logFormat) }.orEmpty()
        val best = records.maxByOrNull { performanceScore(it, logFormat) }?.let { describePerformance(it, logFormat) }.orEmpty()
        val bestE1rm = records
            .mapNotNull { epleyE1rm(it.weight, parseLeadingInt(it.repsText)) }
            .maxOrNull()
        return PerformanceHint(
            lastResult = last,
            bestResult = best,
            lastSessionDate = mostRecentDate,
            lastSessionSets = lastSessionRows,
            bestE1rm = bestE1rm,
        )
    }

    private fun performanceScore(set: WorkoutSetRecord, logFormat: LogFormat): Double = when (logFormat) {
        LogFormat.REPS -> {
            val weightScore = set.weight ?: 0.0
            val repScore = parseLeadingDouble(set.repsText) ?: 0.0
            (weightScore * 1000.0) + repScore
        }
        LogFormat.SECONDS, LogFormat.MINUTES -> {
            val distanceScore = set.distanceKm ?: 0.0
            val durationScore = set.durationSeconds?.toDouble() ?: 0.0
            (distanceScore * 10000.0) + durationScore
        }
    }

    private fun describePerformance(set: WorkoutSetRecord, logFormat: LogFormat): String = when (logFormat) {
        LogFormat.REPS -> {
            val parts = buildList {
                if (set.weight != null) add(formatWeight(set.weight))
                if (!set.repsText.isNullOrBlank()) add("${set.repsText} reps")
                if (set.rpe != null) add("RPE ${set.rpe}")
            }
            parts.joinToString(" • ").ifBlank { "No data" }
        }
        LogFormat.SECONDS, LogFormat.MINUTES -> {
            val parts = buildList {
                if (set.distanceKm != null) add(formatDistance(set.distanceKm))
                if (set.durationSeconds != null) add(formatDuration(set.durationSeconds))
                if (set.paceSecondsPerKm != null) add(formatPace(set.paceSecondsPerKm))
            }
            parts.joinToString(" • ").ifBlank { "No data" }
        }
    }

    private fun getExerciseByName(name: String): ExerciseCatalogItem? {
        readableDb().rawQuery(
            """
            SELECT id, name, default_muscle, default_category, is_bodyweight, log_format, movement_type, default_role, weight_unit, per_side
            FROM exercises
            WHERE name = ? COLLATE NOCASE
            LIMIT 1
            """.trimIndent(),
            arrayOf(name),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ExerciseCatalogItem(
                id = cursor.long("id"),
                name = cursor.string("name"),
                defaultMuscle = cursor.string("default_muscle"),
                defaultCategory = cursor.string("default_category"),
                isBodyweight = cursor.int("is_bodyweight") == 1,
                logFormat = LogFormat.fromDb(cursor.string("log_format")),
                movementType = MovementType.fromDb(cursor.string("movement_type")),
                defaultRole = ProgressionRole.fromDb(cursor.string("default_role")),
                weightUnit = WeightUnit.fromDb(cursor.string("weight_unit")),
                perSide = cursor.intOrNull("per_side") == 1,
                usageCount = 0,
            )
        }
    }

    private fun getComboById(id: Long): ComboItem? {
        readableDb().rawQuery(
            """
            SELECT id, name, discipline, COALESCE(notes, '') AS notes
            FROM combos
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ComboItem(
                id = cursor.long("id"),
                name = cursor.string("name"),
                discipline = cursor.string("discipline"),
                notes = cursor.string("notes"),
            )
        }
    }

    private fun getExerciseIdByName(name: String): Long? {
        readableDb().rawQuery(
            "SELECT id FROM exercises WHERE LOWER(name) = LOWER(?) LIMIT 1",
            arrayOf(name.trim()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.long("id")
        }
    }

    private fun getComboIdByName(name: String): Long? {
        readableDb().rawQuery(
            "SELECT id FROM combos WHERE LOWER(name) = LOWER(?) LIMIT 1",
            arrayOf(name.trim()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.long("id")
        }
    }

    private fun getTemplateIdByName(name: String): Long? {
        readableDb().rawQuery(
            "SELECT id FROM workout_templates WHERE LOWER(name) = LOWER(?) LIMIT 1",
            arrayOf(name.trim()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.long("id")
        }
    }

    private fun getWorkoutPlanIdByName(name: String): Long? {
        readableDb().rawQuery(
            "SELECT id FROM workout_plans WHERE LOWER(name) = LOWER(?) LIMIT 1",
            arrayOf(name.trim()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.long("id")
        }
    }

    private fun readableDb(): SQLiteDatabase {
        ensureDatabaseReady()
        return helper.readableDatabase
    }

    private fun writableDb(): SQLiteDatabase {
        ensureDatabaseReady()
        return helper.writableDatabase
    }

    private fun getAutoBackupTreeUri(): Uri? {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(AUTO_BACKUP_TREE_URI_KEY, null)?.let(Uri::parse)
    }

    private fun getDashboardFeaturedExercises(): List<String> {
        val defaults = listOf("Incline Bench Press", "Viking Press", "Leg Press")
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(DASHBOARD_FEATURED_EXERCISES_KEY, null)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return (saved + defaults).distinct().take(3)
    }

    // Deliberately local time, not UTC (#50): the imported v2 history already
    // stores years of local stamps, and mixing formats would mis-order every
    // comparison against them. Day-level logic reads the Date column instead;
    // the one-hour DST ambiguity only ever touches cosmetic "most recent" ties.
    private fun nowStamp(): String = LocalDateTime.now().format(TIMESTAMP_FORMAT)
}

private class WorkoutDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val appContext = context.applicationContext

    init {
        // Pin WAL regardless of the seed file's journal mode, so export's
        // wal_checkpoint(TRUNCATE) assumption always holds.
        setWriteAheadLoggingEnabled(true)
    }

    fun prepareSeedDatabase() {
        val destination = appContext.getDatabasePath(DATABASE_NAME)
        if (destination.exists()) return
        destination.parentFile?.mkdirs()
        appContext.assets.open(DATABASE_NAME).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        // A seed that doesn't even parse as SQLite would crash on first open with a
        // baffling error; fail loudly at the copy site instead.
        check(destination.length() >= 100 && destination.inputStream().use { stream ->
            val header = ByteArray(16); stream.read(header)
            header.decodeToString(0, 15) == "SQLite format 3"
        }) { "Bundled seed database is not a valid SQLite file" }
    }

    override fun onCreate(db: SQLiteDatabase) {
        ensureAppTables(db)
        runMigrations(db, fromVersion = 0, toVersion = DATABASE_VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureAppTables(db)
        runMigrations(db, fromVersion = oldVersion, toVersion = newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Treat downgrades as no-ops on the schema side — we never destructively remove
        // app tables, and the seed tables are user data we must not touch. Just ensure the
        // app tables still exist so the app can boot. A future major version may want to
        // refuse to open a newer DB rather than silently downgrade — log loudly so we can
        // investigate.
        Log.w(TAG, "Database downgrade requested: $oldVersion -> $newVersion; treating as no-op.")
        ensureAppTables(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureAppTables(db)
        // Defensive: imported databases may have been produced by a tool that lacks the
        // expected indexes on the seed tables. CREATE INDEX IF NOT EXISTS is idempotent
        // and cheap — running it on every open guarantees query plans stay healthy.
        ensureSeedIndexes(db)
    }

    /**
     * Apply ordered migrations from [fromVersion] to [toVersion]. Each step assumes the
     * previous step has already executed. Steps must be idempotent enough to handle
     * partial-failure replay (e.g. use `IF NOT EXISTS` clauses).
     */
    private fun runMigrations(db: SQLiteDatabase, fromVersion: Int, toVersion: Int) {
        if (fromVersion >= toVersion) return
        db.beginTransaction()
        try {
            // v1 -> v2: defensive indexes on seed tables that may have been imported
            // without them.
            if (fromVersion < 2 && toVersion >= 2) {
                ensureSeedIndexes(db)
            }
            // v2 -> v3: martial-arts form progress. The CREATE TABLE itself is
            // also issued unconditionally inside ensureAppTables (called from
            // onOpen), so this migration is the path-of-least-surprise rather
            // than the only route — fresh installs hit ensureAppTables first.
            if (fromVersion < 3 && toVersion >= 3) {
                ensureMartialArtsFormProgressTable(db)
            }
            // v3 -> v4: combos.moves_json — adds an ALTER on the seed combos
            // table. Also re-issued defensively from ensureAppTables on every
            // open so the column exists even if someone imports an older
            // database with no migration path.
            if (fromVersion < 4 && toVersion >= 4) {
                ensureCombosMovesColumn(db)
            }
            // v4 -> v5: goals + form checkpoints. Like v3, these are also
            // issued unconditionally from ensureAppTables; the explicit step
            // documents the version history.
            if (fromVersion < 5 && toVersion >= 5) {
                ensureTrainingGoalsTable(db)
                ensureFormCheckpointsTable(db)
            }
            // v5 -> v6: forms + disciplines become data. Tables are created in
            // ensureAppTables; the one-shot seeding from the Kotlin catalogue
            // happens here so user deletions stick afterwards.
            if (fromVersion < 6 && toVersion >= 6) {
                ensureMartialArtsCatalogTables(db)
                seedMartialArtsCatalog(db)
            }
            // v6 -> v7: re-run the catalogue seed (INSERT OR IGNORE — only the
            // new forms/disciplines land; the Xiao Hong Quan move list arrives
            // via the per-form move seed since the form row already existed),
            // and add the Qigong/Yoga exercises to the library.
            if (fromVersion < 7 && toVersion >= 7) {
                ensureMartialArtsCatalogTables(db)
                seedMartialArtsCatalog(db)
                seedMindBodyExercises(db)
            }
            // v7 -> v8: display-name fix for the Chen form. An UPDATE rather
            // than a re-seed so it also applies to rows the seed created
            // earlier; the stable id keeps progress/checkpoints attached.
            if (fromVersion < 8 && toVersion >= 8) {
                if (tableExists(db, "martial_arts_forms")) {
                    db.execSQL(
                        "UPDATE martial_arts_forms SET title = ?, subtitle = ? WHERE id = ?",
                        arrayOf("陈氏太极拳一路", "89式 · Chen Style Yi Lu", "chen_lao_jia_yi_lu_89"),
                    )
                }
            }
            // Future migrations:
            //   if (fromVersion < 9 && toVersion >= 9) { ... }
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            Log.e(TAG, "Migration $fromVersion -> $toVersion failed", error)
            throw error
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Per-move "learned at" timestamps for any martial-arts form the user is
     * tracking. Identified by a ([formId], [moveIndex]) compound key — formId
     * is one of the static ids defined in [MartialArtsForms]. learned_at is an
     * ISO timestamp and is null/blank for "not yet learned" rows; we keep the
     * row around (rather than deleting) so notes survive an unmark-then-remark.
     */
    private fun ensureMartialArtsFormProgressTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS martial_arts_form_progress (
                form_id TEXT NOT NULL,
                move_index INTEGER NOT NULL,
                learned_at TEXT,
                notes TEXT,
                PRIMARY KEY (form_id, move_index)
            )
            """.trimIndent(),
        )
    }

    /**
     * Training goals (v5). One row per goal; the kind decides which target
     * columns are meaningful (see [GoalKind]). achieved_at is set when the
     * user marks the goal done (or progress computation detects completion).
     */
    private fun ensureTrainingGoalsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS training_goals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                exercise_name TEXT,
                discipline TEXT,
                form_id TEXT,
                target_weight_kg REAL,
                target_distance_km REAL,
                target_duration_seconds INTEGER,
                target_sessions_per_week INTEGER,
                goal_cadence TEXT,
                target_date TEXT,
                notes TEXT,
                created_at TEXT NOT NULL,
                achieved_at TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        // Additive (existing installs): cadence scope:period for CADENCE goals.
        ensureColumn(db, "training_goals", "goal_cadence", "TEXT")
    }

    /**
     * Form checkpoints (v5): "practised form X up to move N on this date".
     * Append-only log written from martial-arts session logging; read by the
     * Martial Arts tab to answer "where did I get up to last time?".
     */
    private fun ensureFormCheckpointsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS martial_arts_form_checkpoints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                form_id TEXT NOT NULL,
                move_index INTEGER NOT NULL,
                notes TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_form_checkpoints_form ON martial_arts_form_checkpoints(form_id, date)")
    }

    /**
     * Forms-as-data tables (v6): the runtime catalogue of forms, their move
     * lists, and the discipline cards. User-editable; seeded once from the
     * Kotlin catalogue by [seedMartialArtsCatalog].
     */
    private fun ensureMartialArtsCatalogTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS martial_arts_forms (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                subtitle TEXT,
                discipline TEXT,
                short_title TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                mastered_at TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS martial_arts_form_moves (
                form_id TEXT NOT NULL,
                move_index INTEGER NOT NULL,
                name TEXT NOT NULL,
                section TEXT,
                PRIMARY KEY (form_id, move_index)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS martial_arts_disciplines (
                name TEXT PRIMARY KEY,
                glyph TEXT,
                tagline TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    /**
     * One-shot seed of forms + disciplines from the Kotlin catalogue.
     * INSERT OR IGNORE keyed on stable ids/names, so re-running (fresh
     * installs hit the 0→N migration path) never duplicates and never
     * resurrects rows the user has since edited or deleted... except on a
     * full reinstall, which is the expected reset semantics.
     */
    private fun seedMartialArtsCatalog(db: SQLiteDatabase) {
        MartialArtsForms.entries.forEachIndexed { index, form ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO martial_arts_forms (id, title, subtitle, discipline, short_title, sort_order)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(form.id, form.title, form.subtitle, form.discipline, form.shortTitle, index.toString()),
            )
            form.moves.forEach { move ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO martial_arts_form_moves (form_id, move_index, name, section)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(form.id, move.index.toString(), move.name, move.section),
                )
            }
        }
        MartialArtsDisciplines.entries.forEachIndexed { index, discipline ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO martial_arts_disciplines (name, glyph, tagline, sort_order)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(discipline.name, discipline.glyph, discipline.tagline, index.toString()),
            )
        }
    }

    /**
     * The Qigong and Yoga exercises (v7): mind-body work logged in minutes,
     * categorised Mobility so it doesn't inflate martial-arts fight hours;
     * the session screen still offers their forms via the discipline match.
     */
    private fun seedMindBodyExercises(db: SQLiteDatabase) {
        if (!tableExists(db, "exercises")) return
        listOf("Qigong", "Yoga").forEach { name ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO exercises (name, default_muscle, default_category, is_bodyweight, log_format)
                VALUES (?, 'Full-Body', 'Mobility', 1, 'minutes')
                """.trimIndent(),
                arrayOf(name),
            )
        }
    }

    /**
     * Add the moves_json column to the seed `combos` table if it's missing.
     * The column holds a JSON array of [ComboMove.id] strings — when present
     * the round timer reads it and calls each move out via TTS. Re-issuing
     * this on every open is cheap (PRAGMA + a no-op when the column already
     * exists) and means even a freshly-imported pre-v4 database picks the
     * column up automatically.
     */
    private fun ensureCombosMovesColumn(db: SQLiteDatabase) {
        if (tableExists(db, "combos")) {
            ensureColumn(db, "combos", "moves_json", "TEXT")
        }
    }

    /**
     * Ensure the seed-data tables (`lifts_new`, `exercises`, `combos`) have the indexes
     * the KPI queries depend on. The seed database we ship already has these, but a
     * user-imported database might not. All statements are guarded by `IF NOT EXISTS`
     * and only run if the underlying table exists.
     */
    private fun ensureSeedIndexes(db: SQLiteDatabase) {
        if (tableExists(db, "lifts_new")) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifts_new_date ON lifts_new(Date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifts_new_exercise_date ON lifts_new(Exercise, Date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifts_new_category_date ON lifts_new(Category, Date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifts_new_discipline_date ON lifts_new(Discipline, Date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifts_new_combo_date ON lifts_new(ComboID, Date)")
        }
        if (tableExists(db, "exercises")) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exercises_category ON exercises(default_category)")
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun ensureAppTables(db: SQLiteDatabase) {
        // 三十六房 (R21): one row per chamber passed. Additive; nothing else touches it.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chamber_passes (
                number INTEGER PRIMARY KEY,
                passed_at TEXT NOT NULL,
                note TEXT
            )
            """.trimIndent(),
        )
        // R22 — 印谱, the seal album: one row per earned achievement seal.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS seal_awards (
                id TEXT PRIMARY KEY,
                earned_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                discipline TEXT,
                notes TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_template_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                template_id INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                exercise_name TEXT,
                combo_id INTEGER,
                combo_name TEXT,
                sets_target INTEGER,
                reps_target TEXT,
                weight_target REAL,
                rest_target_seconds INTEGER,
                duration_target_seconds INTEGER,
                distance_target_km REAL,
                pace_target_seconds_per_km INTEGER,
                rpe_target INTEGER,
                notes TEXT,
                FOREIGN KEY(template_id) REFERENCES workout_templates(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        ensureColumn(db, "workout_template_items", "progression_role", "TEXT NOT NULL DEFAULT 'accessory'")
        ensureColumn(db, "workout_template_items", "superset_id", "TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                workout_date TEXT NOT NULL,
                source_template_id INTEGER,
                status TEXT NOT NULL DEFAULT 'draft',
                notes TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(source_template_id) REFERENCES workout_templates(id) ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        ensureColumn(db, "workout_sessions", "mood", "TEXT")
        ensureColumn(db, "workout_sessions", "diet", "TEXT")
        ensureColumn(db, "workout_sessions", "body_weight_kg", "REAL")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_session_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                exercise_name TEXT,
                combo_id INTEGER,
                combo_name TEXT,
                planned_sets INTEGER,
                target_reps TEXT,
                target_weight REAL,
                target_rest_seconds INTEGER,
                target_duration_seconds INTEGER,
                target_distance_km REAL,
                target_pace_seconds_per_km INTEGER,
                target_rpe INTEGER,
                notes TEXT,
                FOREIGN KEY(session_id) REFERENCES workout_sessions(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        ensureColumn(db, "workout_session_items", "progression_role", "TEXT NOT NULL DEFAULT 'accessory'")
        ensureColumn(db, "workout_session_items", "superset_id", "TEXT")
        ensureColumn(db, "workout_session_items", "coach_note", "TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_session_sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_item_id INTEGER NOT NULL,
                set_index INTEGER NOT NULL,
                weight REAL,
                reps_text TEXT,
                combo_id INTEGER,
                combo_name TEXT,
                rest_seconds INTEGER,
                duration_seconds INTEGER,
                distance_km REAL,
                pace_seconds_per_km REAL,
                rpe INTEGER,
                notes TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(session_item_id) REFERENCES workout_session_items(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        ensureColumn(db, "workout_session_sets", "combo_id", "INTEGER")
        ensureColumn(db, "workout_session_sets", "combo_name", "TEXT")
        ensureColumn(db, "workout_session_sets", "form_id", "TEXT")
        ensureColumn(db, "workout_session_sets", "form_move_index", "INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                start_date TEXT NOT NULL,
                weeks INTEGER NOT NULL DEFAULT 1,
                is_active INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_plan_days (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plan_id INTEGER NOT NULL,
                week_index INTEGER NOT NULL DEFAULT 0,
                day_index INTEGER NOT NULL DEFAULT 0,
                day_label TEXT,
                day_type TEXT NOT NULL DEFAULT 'rest',
                template_id INTEGER,
                template_name TEXT,
                custom_workout_name TEXT,
                FOREIGN KEY(plan_id) REFERENCES workout_plans(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_plan_settings (
                id INTEGER PRIMARY KEY,
                primary_weight_step_kg REAL NOT NULL DEFAULT 2.5,
                secondary_weight_step_kg REAL NOT NULL DEFAULT 2.5,
                accessory_rep_step INTEGER NOT NULL DEFAULT 1,
                accessory_set_step INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        ensureColumn(db, "workout_plan_settings", "emphasis_strength", "INTEGER NOT NULL DEFAULT 40")
        ensureColumn(db, "workout_plan_settings", "emphasis_hypertrophy", "INTEGER NOT NULL DEFAULT 40")
        ensureColumn(db, "workout_plan_settings", "emphasis_power", "INTEGER NOT NULL DEFAULT 20")
        ensureColumn(db, "workout_plan_settings", "weekly_run_km_target", "REAL")
        ensureColumn(db, "workout_plan_settings", "hyp_rep_min", "INTEGER NOT NULL DEFAULT 8")
        ensureColumn(db, "workout_plan_settings", "hyp_rep_max", "INTEGER NOT NULL DEFAULT 12")
        ensureColumn(db, "workout_plan_settings", "strength_sets", "INTEGER NOT NULL DEFAULT 5")
        ensureColumn(db, "workout_plan_settings", "strength_reps", "INTEGER NOT NULL DEFAULT 5")
        ensureColumn(db, "workout_plan_settings", "power_sets", "INTEGER NOT NULL DEFAULT 3")
        ensureColumn(db, "workout_plan_settings", "power_rep_min", "INTEGER NOT NULL DEFAULT 5")
        ensureColumn(db, "workout_plan_settings", "power_rep_max", "INTEGER NOT NULL DEFAULT 8")
        ensureColumn(db, "workout_plan_settings", "power_percent", "INTEGER NOT NULL DEFAULT 50")
        ensureColumn(db, "workout_plan_settings", "deload_after_stalls", "INTEGER NOT NULL DEFAULT 6")
        ensureColumn(db, "workout_plan_settings", "layoff_days", "INTEGER NOT NULL DEFAULT 21")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_muscle_group_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                muscle_group TEXT NOT NULL,
                role TEXT NOT NULL,
                exercise_name TEXT NOT NULL,
                UNIQUE(muscle_group, role, exercise_name)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_muscle_group_pool_state (
                muscle_group TEXT NOT NULL,
                role TEXT NOT NULL,
                seeded_at TEXT NOT NULL,
                PRIMARY KEY (muscle_group, role)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO workout_plan_settings (
                id, primary_weight_step_kg, secondary_weight_step_kg, accessory_rep_step, accessory_set_step
            ) VALUES (1, 2.5, 2.5, 1, 0)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_template_items_template ON workout_template_items(template_id, sort_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_items_session ON workout_session_items(session_id, sort_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_sets_item ON workout_session_sets(session_item_id, set_index)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_workout_plan_days_plan ON workout_plan_days(plan_id, week_index, day_index)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_muscle_group_rules ON workout_muscle_group_rules(muscle_group, role)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_muscle_group_pool_state ON workout_muscle_group_pool_state(muscle_group, role)")
        // Martial-arts form progress (v3): created here so fresh installs hit
        // it on first open without needing the v2→v3 migration path.
        ensureMartialArtsFormProgressTable(db)
        // Combos.moves_json column (v4): defensive ALTER for the seed combos
        // table. ensureColumn is idempotent so the no-op cost is one PRAGMA.
        ensureCombosMovesColumn(db)
        // Training goals + form checkpoints (v5): same fresh-install rationale.
        ensureTrainingGoalsTable(db)
        ensureFormCheckpointsTable(db)
        // Forms/disciplines catalogue tables (v6). Seeding stays in the
        // migration so user deletions stick.
        ensureMartialArtsCatalogTables(db)
        // Plan behaviour toggles + exercise movement type (v6).
        ensureColumn(db, "workout_plans", "repeat_weekly", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "workout_plans", "use_engine", "INTEGER NOT NULL DEFAULT 1")
        if (tableExists(db, "exercises")) {
            ensureColumn(db, "exercises", "movement_type", "TEXT")
            ensureColumn(db, "exercises", "default_role", "TEXT")
            // Per-exercise stack unit (R19). NULL = kg, so every existing row
            // keeps the meaning it already had.
            ensureColumn(db, "exercises", "weight_unit", "TEXT")
            // Per-side exercises (R20). NULL = one side, the old meaning.
            ensureColumn(db, "exercises", "per_side", "INTEGER")
        }
        // Session timing (R5): per-set log time, session start/finish, and a
        // per-row log time in history so total gym time survives export.
        if (tableExists(db, "workout_sessions")) {
            ensureColumn(db, "workout_sessions", "started_at_ms", "INTEGER")
            ensureColumn(db, "workout_sessions", "finished_at_ms", "INTEGER")
        }
        if (tableExists(db, "workout_session_sets")) {
            ensureColumn(db, "workout_session_sets", "logged_at_ms", "INTEGER")
            // Spoken set notes (R20).
            ensureColumn(db, "workout_session_sets", "comment", "TEXT")
        }
        if (tableExists(db, "workout_session_items")) {
            // A note waiting for the set he's on, or a skipped exercise's reason (R20).
            ensureColumn(db, "workout_session_items", "pending_comment", "TEXT")
        }
        if (tableExists(db, "lifts_new")) {
            // Every column the app reads or writes, ensured defensively — a FULL
            // import swaps in an arbitrary (possibly much older) lifts_new, and a
            // missing column would crash queries until the schema catches up.
            ensureColumn(db, "lifts_new", "Rest", "INTEGER")
            ensureColumn(db, "lifts_new", "Notes", "TEXT")
            ensureColumn(db, "lifts_new", "Category", "TEXT")
            ensureColumn(db, "lifts_new", "Muscle", "TEXT")
            ensureColumn(db, "lifts_new", "ComboID", "INTEGER")
            ensureColumn(db, "lifts_new", "Discipline", "TEXT")
            ensureColumn(db, "lifts_new", "Workout", "TEXT")
            ensureColumn(db, "lifts_new", "DurationSeconds", "INTEGER")
            ensureColumn(db, "lifts_new", "DistanceKm", "REAL")
            ensureColumn(db, "lifts_new", "PaceSecondsPerKm", "REAL")
            ensureColumn(db, "lifts_new", "RPE", "INTEGER")
            ensureColumn(db, "lifts_new", "MYWEIGHT", "REAL")
            ensureColumn(db, "lifts_new", "Mood", "TEXT")
            ensureColumn(db, "lifts_new", "Diet", "TEXT")
            ensureColumn(db, "lifts_new", "Superset_ID", "TEXT")
            ensureColumn(db, "lifts_new", "set_type", "TEXT")
            ensureColumn(db, "lifts_new", "LoggedAt", "INTEGER")
            ensureColumn(db, "lifts_new", "Comment", "TEXT")
        }
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {
        // SQLite column names are case-INSENSITIVE — an imported database may
        // carry "Superset_id" where we write "Superset_ID". A case-sensitive
        // compare here once made the ALTER below fail on every open ("duplicate
        // column name"), which aborted ensureAppTables and blanked the whole
        // app. Match case-insensitively, and treat a duplicate-column error as
        // the benign "it already exists" it is — a backup schema quirk must
        // never take the app down.
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.string("name").equals(column, ignoreCase = true)) return
            }
        }
        try {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        } catch (error: android.database.sqlite.SQLiteException) {
            if (error.message?.contains("duplicate column", ignoreCase = true) != true) throw error
            Log.w(TAG, "ensureColumn: $table.$column already exists under another case")
        }
    }
}

