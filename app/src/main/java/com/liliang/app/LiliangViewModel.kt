package com.liliang.app

import com.liliang.app.data.ChamberCatalog
import com.liliang.app.data.ChamberProgress
import com.liliang.app.data.chineseNumeral
import com.liliang.app.data.*
import com.liliang.app.engine.*

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Top-level ViewModel that drives every screen of the app.
 *
 * The [repository] is injected rather than constructed in-place so tests can substitute
 * a fake. The [Factory] companion exposes the default wiring used by production
 * (`viewModel { … }` from MainActivity); tests should construct the ViewModel directly
 * with their own [WorkoutRepository] subclass / fake and a fake [Application].
 *
 * The repository will be split into focused service interfaces in a follow-up step
 * (Phase 2.B2); for now the ViewModel still depends on the concrete class but its
 * lifecycle is no longer tied to construction-time DB I/O.
 */
class LiliangViewModel(
    application: Application,
    private val repository: WorkoutRepository,
) : AndroidViewModel(application) {

    private var nextGeneratedIdSeed = -1L

    var tab by mutableStateOf(LiliangTab.Today)
        private set

    /** The overlay back stack — one BackHandler pops it. */
    val overlays = androidx.compose.runtime.mutableStateListOf<Overlay>()

    fun pushOverlay(overlay: Overlay) {
        overlays.add(overlay)
    }

    fun popOverlay() {
        if (overlays.isEmpty()) return
        val popped = overlays.removeAt(overlays.lastIndex)
        if (popped is Overlay.Session) stashSession()
        if (popped is Overlay.RoundTimer) settleRoundTimer()
    }

    /**
     * All three session writers (autosave, stash, seal) run their DB work under
     * this lock, in dispatch order. Without it the ON_STOP autosave's INSERT
     * could still be in flight when a stash/seal captured the session with
     * id == 0L — a second INSERT and a duplicate row (the background→seal
     * mirror of the race R9 fixed for seal→background).
     */
    private val sessionWriteMutex = Mutex()

    /** The draft id the last autosave INSERTed, keyed by the session's identity
     *  (date + start stamp) so a later stash/seal of the SAME session can adopt
     *  it even when its captured copy still says id == 0L. */
    private var adoptedDraftId: Long? = null
    private var adoptedDraftKey: String? = null

    private fun sessionIdentity(session: WorkoutSession): String =
        "${session.workoutDate}|${session.startedAtMillis ?: 0L}"

    /** The captured session, upgraded with an id the autosave already minted. */
    private fun withAdoptedId(session: WorkoutSession): WorkoutSession =
        if (session.id == 0L && adoptedDraftKey == sessionIdentity(session)) {
            session.copy(id = adoptedDraftId ?: 0L)
        } else {
            session
        }

    /** Failure path for a session write: the workout comes BACK — on screen,
     *  with a status line — instead of evaporating with only a toast. */
    private fun restoreSessionAfterWriteFailure(session: WorkoutSession, what: String, error: Throwable) {
        Log.e(TAG, "$what failed", error)
        currentSession = session
        if (overlays.none { it is Overlay.Session }) pushOverlay(Overlay.Session)
        statusMessage = "$what failed — workout kept on screen (${error.message ?: "write error"})"
    }

    /**
     * Leaving the gym by any route keeps the work: a session with content is
     * written down as a draft, an untouched one just evaporates.
     */
    private fun stashSession() {
        // A session being sealed is NOT a draft to stash. The seal handler calls
        // completeCurrentSession() and then onBack() on the very next line
        // (ui/SessionScreen.kt), and onBack -> popOverlay -> here. Without this
        // guard both paths saw id == 0L and each took the INSERT branch, writing
        // the workout twice — once completed, once as a phantom draft.
        if (completingSession) return
        val session = currentSession ?: return
        currentSession = null
        clearSessionScratch()
        // Only keep a draft if there's real progress (a set was logged) or it
        // was already a saved draft being resumed. Merely opening a planned
        // workout and backing out must NOT leave a ghost draft behind.
        val hasLogged = session.items.any { it.loggedSets.isNotEmpty() }
        val isExistingDraft = session.id != 0L
        if (!hasLogged && !isExistingDraft) return
        load {
            sessionWriteMutex.withLock {
                try {
                    repository.saveSession(withAdoptedId(session).withDefaultName().copy(status = SessionStatus.DRAFT))
                } catch (error: Exception) {
                    restoreSessionAfterWriteFailure(session, "Draft save", error)
                    return@load
                }
            }
            refreshAfterHistoryChange()
            statusMessage = "Saved as draft"
        }
    }

    /**
     * Process-death safety net, called from the Activity's onStop: a live
     * session with any logged work is quietly persisted as a draft. The id is
     * adopted so subsequent saves (and the eventual completion) update the
     * same row instead of forking a duplicate. In-memory item ids stay
     * untouched, so nothing on the session screen resets.
     */
    fun autosaveSessionDraft() {
        val session = currentSession ?: return
        val hasWork = session.items.any { it.loggedSets.isNotEmpty() }
        if (!hasWork && session.id == 0L) return
        load {
            sessionWriteMutex.withLock {
                val toSave = withAdoptedId(session)
                val savedId = repository.saveSession(
                    toSave.withDefaultName().copy(status = SessionStatus.DRAFT),
                    autoExport = false,
                )
                if (toSave.id == 0L) {
                    // Record the minted id for any writer whose captured copy
                    // predates this save — and adopt it into the live session.
                    adoptedDraftId = savedId
                    adoptedDraftKey = sessionIdentity(session)
                    currentSession = currentSession
                        ?.takeIf { sessionIdentity(it) == sessionIdentity(session) && it.id == 0L }
                        ?.copy(id = savedId)
                        ?: currentSession
                }
            }
        }
    }

    fun consumeStatus() {
        statusMessage = null
        statusActionLabel = null
        statusAction = null
    }

    /** One-line notices from screens (e.g. "media volume is 0 — bells silent"). */
    fun notice(message: String) {
        statusMessage = message
    }

    /** True while a timed hold is counting — the session pager locks so a stray
     *  swipe can't dispose the hold clock mid-hang (a disposed page cancels its
     *  LaunchedEffect: no ticks, no finish bell, eyes-off he'd never know). */
    var holdTimerActive by mutableStateOf(false)
        private set

    fun setHoldActive(active: Boolean) {
        holdTimerActive = active
    }

    /** Pop overlays until (and including) the topmost instance of [target]. */
    private fun popThrough(target: Overlay) {
        val index = overlays.lastIndexOf(target)
        if (index >= 0) {
            while (overlays.size > index) overlays.removeAt(overlays.lastIndex)
        }
    }

    /** Bring the session overlay to the top without stacking duplicates. */
    private fun openSessionOverlay() {
        // Stamp the start time the first time a session opens; a reopened draft
        // keeps the start it was created with (loaded from the DB). A session
        // dated in the future is being BROWSED, not begun — no stamp, no clock.
        currentSession?.let { s ->
            if (s.startedAtMillis == null && s.workoutDate <= today()) {
                currentSession = s.copy(startedAtMillis = System.currentTimeMillis())
            }
        }
        if (overlays.lastOrNull() !is Overlay.Session) pushOverlay(Overlay.Session)
    }

    var dashboardSnapshot by mutableStateOf(DashboardSnapshot())
        private set

    var plannerSnapshot by mutableStateOf(PlannerSnapshot())
        private set

    var plannerTemplate by mutableStateOf(repository.emptyTemplate())
        private set

    var draftSessions by mutableStateOf(emptyList<WorkoutSession>())
        private set

    var currentSession by mutableStateOf<WorkoutSession?>(null)
        private set

    var todayPlanRecommendation by mutableStateOf<PlanRecommendation?>(null)
        private set

    var selectedTodayDate by mutableStateOf(today())
        private set

    var selectedDaySnapshot by mutableStateOf(DayWorkoutSnapshot())
        private set

    var configSnapshot by mutableStateOf(ConfigSnapshot())
        private set

    var exerciseEditor by mutableStateOf(newExerciseEditor())
        private set

    var comboEditor by mutableStateOf(ComboItem(name = "", discipline = "", notes = ""))
        private set

    var muscleGroupRuleEditor by mutableStateOf(MuscleGroupRule())
        private set

    var planEditor by mutableStateOf(newPlanEditor())
        private set

    var planSettingsEditor by mutableStateOf(WorkoutPlanSettings())
        private set

    /**
     * Per-form progress maps for the martial-arts tab and dashboard tile.
     * Keyed by [MartialArtsForm.id]. Populated lazily on first access of the
     * martial-arts tab or whenever a move is toggled. Empty map until then.
     */
    var martialArtsFormProgress by mutableStateOf<Map<String, MartialArtsFormProgress>>(emptyMap())
        private set

    var plannerWeekIndex by mutableIntStateOf(0)
        private set

    private var pendingPlanAssignment: Pair<Int, Int>? = null

    var statusMessage by mutableStateOf<String?>(null)
        private set

    /** Optional action riding the status toast (e.g. UNDO after a set delete). */
    var statusActionLabel by mutableStateOf<String?>(null)
        private set
    var statusAction by mutableStateOf<(() -> Unit)?>(null)
        private set

    var isBusy by mutableStateOf(false)
        private set

    init {
        refreshAll()
        autoIngestOnLaunch()
    }

    /**
     * Midnight rollover, reported by the app-scope watcher: every today-derived
     * snapshot (calendar isToday stamps, scheduled-vs-future states, the streak)
     * follows the real day instead of the day the app was opened on.
     */
    /** The calendar's scroll position, kept across tab switches. */
    private var calendarListStateHolder: androidx.compose.foundation.lazy.LazyListState? = null

    fun obtainCalendarListState(initialIndex: Int): androidx.compose.foundation.lazy.LazyListState =
        calendarListStateHolder ?: androidx.compose.foundation.lazy.LazyListState(
            firstVisibleItemIndex = initialIndex,
        ).also { calendarListStateHolder = it }

    fun onDayChanged() {
        load {
            invalidateCalendarWindow()
            refreshAfterHistoryChange()
        }
    }

    fun selectTab(target: LiliangTab) {
        // Settle BEFORE stashing: settlement writes rounds through the live
        // session, and the reverse order would discard them against a nulled
        // currentSession. Unreachable today (the dock hides under overlays),
        // but the order must be safe for any future caller.
        if (overlays.any { it is Overlay.RoundTimer }) settleRoundTimer()
        if (overlays.any { it is Overlay.Session }) stashSession()
        overlays.clear()
        tab = target
        // Rooms re-read their world on entry — a tab must never show stale state.
        if (target == LiliangTab.Hall) refreshMartialArtsForms()
        if (target == LiliangTab.Today) refreshToday()
    }

    fun selectExercise(name: String) {
        refreshDashboard(selectedExercise = name)
        if (overlays.lastOrNull() !is Overlay.ExerciseDetail) pushOverlay(Overlay.ExerciseDetail(name))
    }

    fun openTodayFromDashboard() {
        tab = LiliangTab.Today
        overlays.clear()
        selectTodayDate(today())
    }

    fun refreshAll() {
        refreshDashboard(selectedExercise = dashboardSnapshot.selectedExerciseSummary?.exerciseName)
        refreshPlanner(resetEditor = plannerTemplate.id == 0L && plannerTemplate.items.isEmpty())
        refreshToday()
        refreshConfig()
    }

    fun refreshDashboard(selectedExercise: String? = dashboardSnapshot.selectedExerciseSummary?.exerciseName) {
        load {
            val snapshot = repository.loadDashboard("", selectedExercise)
            dashboardSnapshot = snapshot
        }
    }

    fun refreshPlanner(resetEditor: Boolean = false) {
        load {
            val snapshot = repository.loadPlannerSnapshot()
            plannerSnapshot = snapshot
            if (resetEditor && plannerTemplate.id == 0L && plannerTemplate.items.isEmpty()) {
                plannerTemplate = snapshot.templates.firstOrNull() ?: repository.emptyTemplate()
            }
        }
    }

    /** The Today room's own snapshot — always real today, never moved by the calendar. */
    var todaySnapshot by mutableStateOf(DayWorkoutSnapshot())
        private set

    fun refreshToday() {
        load {
            refreshAfterHistoryChange()
            currentSession = currentSession?.let { existing ->
                if (existing.id == 0L) existing else repository.loadSession(existing.id)
            }
        }
    }

    fun refreshConfig() {
        load {
            configSnapshot = repository.loadConfigSnapshot()
            planSettingsEditor = configSnapshot.planSettings
            ensurePlannerPlanLoaded()
        }
    }

    /** Set the role an exercise takes by default when added to a workout. */
    fun setExerciseDefaultRole(name: String, role: ProgressionRole) {
        load {
            repository.setExerciseDefaultRole(name, role)
            configSnapshot = repository.loadConfigSnapshot()
        }
    }

    /** Set the unit an exercise's stack is labelled in (the KG/LB dial toggle). */
    fun setExerciseWeightUnit(name: String, unit: WeightUnit) {
        load {
            repository.setExerciseWeightUnit(name, unit)
            configSnapshot = repository.loadConfigSnapshot()
        }
    }

    // ───────────────────────── Training goals ─────────────────────────

    var goalEditor by mutableStateOf(TrainingGoal())
        private set

    /** Live progress for every goal — drives the goals list and dashboard card. */
    var goalProgressList by mutableStateOf<List<GoalProgress>>(emptyList())
        private set

    fun refreshGoalProgress() {
        load {
            goalProgressList = repository.getGoalProgressList()
        }
    }

    /** Jump from the dashboard goals card straight to the goals list. */
    fun openGoalsFromDashboard() {
        pushOverlay(Overlay.Goals)
        refreshGoalProgress()
    }

    fun loadGoalEditor(goal: TrainingGoal?) {
        goalEditor = goal ?: TrainingGoal()
        pushOverlay(Overlay.GoalEditor)
    }

    fun updateGoalEditor(transform: (TrainingGoal) -> TrainingGoal) {
        goalEditor = transform(goalEditor)
    }

    fun saveGoalEditor() {
        val goal = goalEditor.withDefaultTitle()
        if (goal.title.isBlank()) {
            statusMessage = "Goal needs a title"
            return
        }
        load {
            repository.saveTrainingGoal(goal)
            configSnapshot = repository.loadConfigSnapshot()
            goalProgressList = repository.getGoalProgressList()
            popThrough(Overlay.GoalEditor)
            statusMessage = "Goal saved"
        }
    }

    fun deleteGoal(goalId: Long) {
        load {
            repository.deleteTrainingGoal(goalId)
            configSnapshot = repository.loadConfigSnapshot()
            goalProgressList = repository.getGoalProgressList()
            popThrough(Overlay.GoalEditor)
            statusMessage = "Goal deleted"
        }
    }

    fun setGoalAchieved(goalId: Long, achieved: Boolean) {
        load {
            repository.setTrainingGoalAchieved(goalId, achieved)
            configSnapshot = repository.loadConfigSnapshot()
            goalProgressList = repository.getGoalProgressList()
            statusMessage = if (achieved) "Carved as achieved 成" else "Goal reopened"
        }
    }

    /** Generate a sensible title from the goal's own numbers when none was typed. */
    private fun TrainingGoal.withDefaultTitle(): TrainingGoal {
        if (title.isNotBlank()) return this
        val generated = when (kind) {
            GoalKind.LIFT_TARGET -> targetWeightKg?.let { "${formatWeight(it)} ${exerciseName.ifBlank { "lift" }}" }.orEmpty()
            GoalKind.RUN_TIME -> {
                val d = targetDistanceKm
                val t = targetDurationSeconds
                if (d != null && t != null) "${formatDistance(d)} in ${formatDuration(t)}" else ""
            }
            GoalKind.RUN_DISTANCE -> targetDistanceKm?.let { "Run ${formatDistance(it)}" }.orEmpty()
            GoalKind.BODYWEIGHT -> targetWeightKg?.let { "Bodyweight ${formatWeight(it)}" }.orEmpty()
            GoalKind.CADENCE -> targetSessionsPerWeek?.let {
                val what = if (cadenceScope == CadenceScope.RUNNING) "Run" else "Train"
                val per = if (cadencePeriod == CadencePeriod.WEEK) "week" else "month"
                "$what ${it}× a $per"
            }.orEmpty()
            GoalKind.DISCIPLINE_SESSIONS -> targetSessionsPerWeek?.let { "$discipline ${it}×/week" }.orEmpty()
            GoalKind.FORM_MASTERY -> martialArtsForms.firstOrNull { it.id == formId }?.let { "Master ${it.displayShortTitle}" }.orEmpty()
            GoalKind.INSPIRATION -> notes.take(48)
        }
        return copy(title = generated)
    }

    fun editTemplate(template: WorkoutTemplate) {
        plannerTemplate = template.withEditableIds(::nextGeneratedId)
        pushOverlay(Overlay.WorkoutEditor)
    }

    fun newTemplate() {
        plannerTemplate = repository.emptyTemplate()
        pendingPlanAssignment = null
        pushOverlay(Overlay.WorkoutEditor)
    }

    fun newTemplateForPlanDay(weekIndex: Int, dayIndex: Int) {
        plannerTemplate = repository.emptyTemplate()
        pendingPlanAssignment = weekIndex to dayIndex
        pushOverlay(Overlay.WorkoutEditor)
    }

    fun cloneRecentWorkoutToPlanner(workout: WorkoutGroup) {
        plannerTemplate = WorkoutTemplate(
            name = workout.displayName,
            discipline = workout.entries.firstOrNull()?.discipline.orEmpty(),
            items = repository.buildTemplateItemsFromWorkout(workout).normalizeTemplateItems(),
        )
        pushOverlay(Overlay.WorkoutEditor)
    }

    fun updatePlannerTemplate(transform: (WorkoutTemplate) -> WorkoutTemplate) {
        plannerTemplate = transform(plannerTemplate)
    }

    /** Add to the editor with the exercise's own default role (Settings → roles). */
    fun addPlannerExercise(name: String) {
        if (name.isBlank()) return
        addPlannerExercise(name, repository.roleForExercise(name))
    }

    fun addPlannerExercise(name: String, role: ProgressionRole) {
        if (name.isBlank()) return
        plannerTemplate = plannerTemplate.copy(
            items = (plannerTemplate.items + WorkoutTemplateItem(
                id = nextGeneratedId(),
                exerciseName = name,
                progressionRole = role,
                hints = repository.getHintForExercise(name),
            )).normalizeTemplateItems(),
        )
    }

    fun addPlannerCombo(combo: ComboItem) {
        plannerTemplate = plannerTemplate.copy(
            items = (plannerTemplate.items + WorkoutTemplateItem(
                id = nextGeneratedId(),
                comboId = combo.id,
                comboName = combo.name,
            )).normalizeTemplateItems(),
        )
    }

    fun updatePlannerItem(itemId: Long, transform: (WorkoutTemplateItem) -> WorkoutTemplateItem) {
        plannerTemplate = plannerTemplate.copy(
            items = plannerTemplate.items.map { item ->
                if (item.id == itemId) transform(item) else item
            }.normalizeTemplateItems(),
        )
    }

    /** Nudge a template exercise up or down; sortOrder is renumbered to match. */
    fun movePlannerItem(itemId: Long, delta: Int) {
        updatePlannerTemplate { template ->
            val items = template.items.sortedBy { it.sortOrder }.toMutableList()
            val from = items.indexOfFirst { it.id == itemId }
            val to = from + delta
            if (from < 0 || to < 0 || to >= items.size) return@updatePlannerTemplate template
            items.add(to, items.removeAt(from))
            template.copy(items = items.mapIndexed { index, item -> item.copy(sortOrder = index) })
        }
    }

    fun removePlannerItem(itemId: Long) {
        plannerTemplate = plannerTemplate.copy(
            items = plannerTemplate.items.filterNot { it.id == itemId }.normalizeTemplateItems(),
        )
    }

    fun savePlannerTemplate() {
        val template = plannerTemplate
        if (template.name.isBlank()) {
            statusMessage = "Template name required"
            return
        }
        load {
            val savedId = repository.saveTemplate(template)
            val snapshot = repository.loadPlannerSnapshot()
            plannerSnapshot = snapshot
            plannerTemplate = snapshot.templates.firstOrNull { it.id == savedId } ?: repository.emptyTemplate()
            pendingPlanAssignment?.let { (weekIndex, dayIndex) ->
                val assignedPlan = planEditor.copy(
                    days = planEditor.days.map { day ->
                        if (day.weekIndex == weekIndex && day.dayIndex == dayIndex) {
                            day.copy(
                                dayType = PlanDayType.TEMPLATE,
                                templateId = savedId,
                                templateName = snapshot.templates.firstOrNull { it.id == savedId }?.name.orEmpty(),
                                customWorkoutName = "",
                            )
                        } else day
                    },
                )
                repository.saveWorkoutPlan(assignedPlan)
                planEditor = assignedPlan
            }
            pendingPlanAssignment = null
            popThrough(Overlay.WorkoutEditor)
            configSnapshot = repository.loadConfigSnapshot()
            planSettingsEditor = configSnapshot.planSettings
            statusMessage = "Template saved"
        }
    }

    fun deletePlannerTemplate(templateId: Long) {
        load {
            repository.deleteTemplate(templateId)
            val snapshot = repository.loadPlannerSnapshot()
            plannerSnapshot = snapshot
            if (plannerTemplate.id == templateId) {
                plannerTemplate = repository.emptyTemplate()
            }
            pendingPlanAssignment = null
            popThrough(Overlay.WorkoutEditor)
            statusMessage = "Template deleted"
        }
    }

    fun startTemplateWorkout(templateId: Long, useEngine: Boolean = true, date: String = today()) {
        val template = plannerSnapshot.templates.firstOrNull { it.id == templateId } ?: return
        // buildSessionFromTemplate consults the progression engine, which reads
        // per-exercise history — keep it off the main thread.
        load {
            currentSession = repository.buildSessionFromTemplate(template, date = date, useEngine = useEngine).withEditableIds(::nextGeneratedId)
            openSessionOverlay()
        }
    }

    fun startRecommendedWorkout() {
        val recommendation = selectedDaySnapshot.recommendation ?: todayPlanRecommendation ?: return
        startFromRecommendation(recommendation)
    }

    /** Begin today's edict — used by the Today room, anchored to real today. */
    fun startTodayRecommended() {
        val recommendation = todaySnapshot.recommendation ?: todayPlanRecommendation ?: return
        startFromRecommendation(recommendation)
    }

    private fun startFromRecommendation(recommendation: PlanRecommendation) {
        when (recommendation.dayType) {
            // The recommendation's own date rides along — exploring next month's
            // plan day must open a session ON that day, not one dated today.
            PlanDayType.TEMPLATE -> recommendation.templateId?.let { startTemplateWorkout(it, recommendation.useEngine, recommendation.date) }
            PlanDayType.CUSTOM -> load {
                currentSession = repository.emptySession(recommendation.date).copy(name = recommendation.customWorkoutName.ifBlank { recommendation.dayLabel })
                openSessionOverlay()
            }
            PlanDayType.REST -> statusMessage = "Rest day"
        }
    }

    fun startCustomWorkout(date: String = selectedTodayDate) {
        load {
            currentSession = repository.emptySession(date)
            openSessionOverlay()
        }
    }

    fun startCustomWorkoutFromTemplate(templateId: Long, date: String = selectedTodayDate) {
        val template = plannerSnapshot.templates.firstOrNull { it.id == templateId } ?: return
        load {
            currentSession = repository.buildSessionFromTemplate(template, date).withEditableIds(::nextGeneratedId)
            openSessionOverlay()
        }
    }

    /**
     * Start a session whose structure copies a recently completed workout —
     * "do what I did last Tuesday". The workout group is converted to a
     * throwaway template, then run through the progression engine so the
     * prescriptions are current rather than a verbatim replay.
     */
    fun startWorkoutFromRecent(workout: WorkoutGroup, date: String = selectedTodayDate) {
        load {
            val template = WorkoutTemplate(
                name = workout.workoutName.ifBlank { "Workout" },
                items = repository.buildTemplateItemsFromWorkout(workout),
            )
            currentSession = repository.buildSessionFromTemplate(template, date).withEditableIds(::nextGeneratedId)
            openSessionOverlay()
        }
    }

    // ───────────────────────── Quick logging ─────────────────────────

    /** Engine suggestion shown at the top of the run quick-log dialog. */
    var quickRunCoach by mutableStateOf<RunPrescription?>(null)
        private set

    /** Refresh the coach suggestion before opening the run quick-log dialog. */
    fun prepareRunQuickLog() {
        load {
            quickRunCoach = if (repository.isCoachEnabled()) repository.getRunPrescription() else null
        }
    }

    /** Flip the coach (progression engine voice) on or off, everywhere. */
    fun setCoachEnabled(enabled: Boolean) {
        load {
            repository.setCoachEnabled(enabled)
            configSnapshot = repository.loadConfigSnapshot()
            if (!enabled) dayCoachPreview = null
            statusMessage = if (enabled) "Coach is on" else "Coach is off — raw targets only"
        }
    }

    fun setRestChimeEnabled(enabled: Boolean) {
        load {
            repository.setRestChimeEnabled(enabled)
            configSnapshot = repository.loadConfigSnapshot()
            statusMessage = if (enabled) "Rest-over chime is on" else "Rest-over chime is off"
        }
    }

    /** Choose which forms tile the dashboard (empty = none). */
    fun setDashboardFeaturedForms(ids: List<String>) {
        load {
            repository.setDashboardFeaturedFormIds(ids)
            configSnapshot = repository.loadConfigSnapshot()
        }
    }

    fun logQuickRun(
        date: String,
        title: String,
        distanceKm: Double?,
        durationSeconds: Int?,
        paceSecondsPerKm: Double?,
        notes: String,
    ) {
        load {
            repository.logQuickRun(date, title, distanceKm, durationSeconds, paceSecondsPerKm, notes)
            refreshAfterQuickLog(date)
            statusMessage = "Run logged"
        }
    }

    fun logQuickMartialArts(
        date: String,
        discipline: String,
        exerciseName: String,
        durationSeconds: Int?,
        notes: String,
        formId: String,
        reachedMoveIndex: Int?,
    ) {
        load {
            repository.logQuickMartialArts(date, discipline, exerciseName, durationSeconds, notes, formId, reachedMoveIndex)
            refreshAfterQuickLog(date)
            statusMessage = "Session logged"
        }
    }

    /** Shared refresh after a quick log lands directly in history. */
    private suspend fun refreshAfterQuickLog(date: String) {
        refreshAfterHistoryChange(affectedDates = listOf(date))
    }

    // ───────────────────────── Round timer ─────────────────────────

    /** Live config for the round-timer overlay; null when it isn't open. */
    var roundTimerSetup by mutableStateOf<RoundTimerSetup?>(null)
        private set

    /** Rounds finished so far, mirrored live so ANY exit path logs honestly. */
    private var roundTimerCompletedRounds = 0

    /** How many of those rounds have already been written down (session sets /
     *  the Hall quick log). Rounds used to exist only in memory until the
     *  overlay popped — process death four rounds in lost all four. */
    private var roundTimerLoggedRounds = 0

    fun startRoundTimer(setup: RoundTimerSetup) {
        if (setup.rounds.isEmpty()) return
        roundTimerSetup = setup
        roundTimerCompletedRounds = 0
        roundTimerLoggedRounds = 0
        pushOverlay(Overlay.RoundTimer)
    }

    /**
     * Each round is committed the moment it completes — into the session item
     * for gym bag work (durable via the ON_STOP autosave), or as one Hall quick
     * log when the last round lands. Settlement then has nothing left to write.
     */
    fun reportRoundTimerProgress(completedRounds: Int) {
        roundTimerCompletedRounds = completedRounds
        val setup = roundTimerSetup ?: return
        when (val origin = setup.origin) {
            is RoundTimerOrigin.SessionItem -> {
                while (roundTimerLoggedRounds < completedRounds) {
                    val i = roundTimerLoggedRounds
                    val combo = setup.rounds[i % setup.rounds.size]
                    commitLoggedSet(origin.itemId) {
                        it.copy(
                            comboId = combo.id.takeIf { id -> id > 0 },
                            comboName = combo.name,
                            durationSeconds = setup.workSeconds,
                        )
                    }
                    roundTimerLoggedRounds++
                }
            }
            is RoundTimerOrigin.Hall -> {
                // Hall drills settle as ONE history row — write it as soon as
                // the final round finishes rather than waiting for the pop.
                if (completedRounds >= setup.rounds.size && roundTimerLoggedRounds < completedRounds) {
                    roundTimerLoggedRounds = completedRounds
                    logHallRounds(setup, origin, completedRounds)
                }
            }
        }
    }

    private fun logHallRounds(setup: RoundTimerSetup, origin: RoundTimerOrigin.Hall, completed: Int) {
        load {
            val comboNames = (0 until completed)
                .mapNotNull { setup.rounds.getOrNull(it % setup.rounds.size)?.name }
                .distinct()
            repository.logQuickMartialArts(
                date = today(),
                discipline = origin.discipline,
                exerciseName = "Bag rounds",
                durationSeconds = completed * setup.workSeconds,
                notes = comboNames.joinToString(", "),
            )
            refreshAfterHistoryChange(affectedDates = listOf(today()))
            // The Hall's own numbers (THIS YEAR / TOTAL, the pulse) must include
            // the drill that just logged — the toast said so while the page
            // disagreed until the tab was re-entered.
            martialArtsHall = repository.loadMartialArtsHall()
        }
    }

    /** Close the timer through the normal stack — settlement logs the rounds. */
    fun finishRoundTimer() {
        if (overlays.lastOrNull() is Overlay.RoundTimer) popOverlay()
    }

    /**
     * The timer left the screen (button, system back, tab switch). Session
     * rounds are already committed per round; a partial Hall drill logs its
     * completed rounds here. Runs once: clearing the setup makes a second
     * settle a no-op.
     */
    private fun settleRoundTimer() {
        val setup = roundTimerSetup ?: return
        val completed = roundTimerCompletedRounds
        val alreadyLogged = roundTimerLoggedRounds
        roundTimerSetup = null
        roundTimerCompletedRounds = 0
        roundTimerLoggedRounds = 0
        if (completed <= 0) return
        val summary = if (completed == 1) "1 round logged" else "$completed rounds logged"
        when (val origin = setup.origin) {
            is RoundTimerOrigin.SessionItem -> {
                // Per-round commits already wrote these; just report.
                statusMessage = summary
            }
            is RoundTimerOrigin.Hall -> {
                if (alreadyLogged < completed) logHallRounds(setup, origin, completed)
                statusMessage = summary
            }
        }
    }

    /** Write [count] rounds onto a session item, each with its round's combo. */
    fun logBagRounds(itemId: Long, rounds: List<ComboItem>, count: Int, workSeconds: Int) {
        if (rounds.isEmpty() || count <= 0) return
        repeat(count) { i ->
            val combo = rounds[i % rounds.size]
            commitLoggedSet(itemId) {
                it.copy(
                    comboId = combo.id.takeIf { id -> id > 0 },
                    comboName = combo.name,
                    durationSeconds = workSeconds,
                )
            }
        }
    }

    // ─────────────────── Bag plans (per-round combo setup) ───────────────────

    /**
     * Round plans under construction, keyed per session item and held here so
     * rotation can't wipe a half-built plan. A single shared slot used to leak:
     * a second bag exercise in the same session wiped the first one's rounds on
     * swipe-back, and template-built items reuse ids (-(index+1)) so an
     * abandoned session's plan could surface as the NEXT session's — the map is
     * cleared whenever the live session goes away.
     */
    var bagPlans by mutableStateOf<Map<Long, BagPlan>>(emptyMap())
        private set

    /** Seed a plan the first time a bag exercise's stage shows for this item. */
    fun ensureBagPlan(item: WorkoutSessionItem, combos: List<ComboItem>) {
        if (bagPlans.containsKey(item.id)) return
        val seedCombo = combos.firstOrNull { it.id == item.comboId }
            ?: item.loggedSets.lastOrNull { it.comboId != null }
                ?.comboId?.let { id -> combos.firstOrNull { it.id == id } }
        val roundCount = (item.plannedSets ?: 3).coerceIn(1, 20)
        bagPlans = bagPlans + (
            item.id to BagPlan(
                itemId = item.id,
                rounds = seedCombo?.let { c -> List(roundCount) { c } } ?: emptyList(),
                workSeconds = item.targetDurationSeconds ?: 180,
                restSeconds = item.targetRestSeconds ?: 60,
                callMoves = false,
            )
            )
    }

    fun updateBagPlan(itemId: Long, transform: (BagPlan) -> BagPlan) {
        val plan = bagPlans[itemId] ?: return
        bagPlans = bagPlans + (itemId to transform(plan))
    }

    /** Session-scoped scratch state that must not outlive the session. */
    private fun clearSessionScratch() {
        bagPlans = emptyMap()
    }

    /** Save an on-the-spot combo into the library so it's reusable later. */
    fun saveComboToLibrary(combo: ComboItem) {
        load {
            repository.saveCombo(combo)
            configSnapshot = repository.loadConfigSnapshot()
        }
    }

    // ────────── Form + discipline editors (VM-backed: rotation-safe) ──────────
    // Loaded at PUSH time by the opener; the screens only read/update, so a
    // configuration change can't wipe half-typed edits.

    var formEditor by mutableStateOf(MartialArtsForm(id = "", title = ""))
        private set

    fun loadFormEditorFor(formId: String?, seedDiscipline: String) {
        formEditor = formId?.let { id -> martialArtsForms.firstOrNull { it.id == id } }
            ?: MartialArtsForm(id = "", title = "", discipline = seedDiscipline)
    }

    fun updateFormEditor(transform: (MartialArtsForm) -> MartialArtsForm) {
        formEditor = transform(formEditor)
    }

    var disciplineEditor by mutableStateOf(MartialArtsDiscipline("", "", ""))
        private set

    fun newDisciplineEditor() {
        disciplineEditor = MartialArtsDiscipline("", "", "")
    }

    fun updateDisciplineEditor(transform: (MartialArtsDiscipline) -> MartialArtsDiscipline) {
        disciplineEditor = transform(disciplineEditor)
    }

    /**
     * Save a brand-new exercise to the library, then add it to the current
     * session — the "that exercise isn't in your library yet" flow.
     */
    fun createExerciseAndAddToSession(item: ExerciseCatalogItem) {
        load {
            try {
                repository.saveExercise(item)
            } catch (error: IllegalArgumentException) {
                statusMessage = error.message
                return@load
            }
            configSnapshot = repository.loadConfigSnapshot()
            addSessionExercise(item.name)
            statusMessage = "${item.name} saved to library"
        }
    }

    fun openDraftSession(sessionId: Long) {
        load {
            val loaded = repository.loadSession(sessionId)?.withEditableIds(::nextGeneratedId)
            // A scheduled draft opened on/after its day gets TODAY's numbers —
            // the ingest prescribed it with last Sunday's history (⚖ B4).
            currentSession = loaded?.let { session ->
                if (session.workoutDate <= today() && session.items.none { it.loggedSets.isNotEmpty() }) {
                    repository.represcribeSessionForToday(session)
                } else {
                    session
                }
            }
            openSessionOverlay()
        }
    }

    /** Remove a discipline card from the Hall (long-press → confirm). History stays. */
    fun deleteDiscipline(name: String) {
        load {
            repository.deleteMartialArtsDiscipline(name)
            val hall = repository.loadMartialArtsHall()
            martialArtsHall = hall
            statusMessage = "$name removed from the Hall — history untouched"
        }
    }

    fun closeCurrentSession() {
        currentSession = null
        clearSessionScratch()
    }

    // ───────────────────────── Scrolling calendar ─────────────────────────

    /**
     * The months the scrolling calendar currently shows, oldest first. Grows
     * as the user scrolls (forward automatically, backward via "earlier").
     */
    var calendarWindow by mutableStateOf<List<YearMonth>>(emptyList())
        private set

    /** Loaded month snapshots, keyed by month. */
    var calendarSnapshots by mutableStateOf<Map<YearMonth, CalendarMonthSnapshot>>(emptyMap())
        private set

    /** Months currently being fetched, so visibility events don't double-load. */
    private val calendarRequestsInFlight: MutableSet<YearMonth> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The calendar window is FIXED — two years of history through three
     * months ahead, created once. No prepending while scrolling means stable
     * list indices: no anchor jumps, no jitter. Months load lazily as their
     * cells approach the viewport; "earlier" extends the window in one
     * deliberate step for the rare deep-history dive.
     */
    fun ensureCalendarWindow() {
        if (calendarWindow.isEmpty()) {
            val now = YearMonth.now()
            calendarWindow = (24L downTo 1L).map { now.minusMonths(it) } +
                listOf(now) +
                (1L..3L).map { now.plusMonths(it) }
        }
        // Priority order: what the user sees first loads first.
        val now = YearMonth.now()
        requestCalendarMonth(now)
        requestCalendarMonth(now.minusMonths(1))
        requestCalendarMonth(now.plusMonths(1))
    }

    /** Fetch one month if it isn't cached or already on its way. */
    fun requestCalendarMonth(month: YearMonth) {
        if (calendarSnapshots.containsKey(month)) return
        if (!calendarRequestsInFlight.add(month)) return
        load {
            try {
                val snapshot = repository.loadCalendarMonth(month)
                // Merge on Main: three months load in parallel, and an IO-side
                // read-modify-write could drop a sibling's just-added snapshot.
                withContext(Dispatchers.Main) {
                    calendarSnapshots = calendarSnapshots + (month to snapshot)
                }
            } finally {
                calendarRequestsInFlight.remove(month)
            }
        }
    }

    /** Add another year of history to the top of the window. */
    fun extendCalendarPast() {
        if (calendarWindow.isEmpty()) return
        val first = calendarWindow.first()
        calendarWindow = (12L downTo 1L).map { first.minusMonths(it) } + calendarWindow
    }

    /**
     * Drop cached months after history changes. Visible month items notice
     * their snapshot vanished and re-request it themselves, so only what's
     * on screen reloads — and the scroll position never moves.
     *
     * With [affectedDates] the drop is surgical: only the months those dates
     * live in (plus neighbours, for grid spillover) reload; two years of
     * scrolled-back cache survives a single workout edit. No dates = full
     * clear, which plan-shaped changes still need (they tint every month).
     */
    private fun invalidateCalendarWindow(affectedDates: Collection<String> = emptyList()) {
        if (calendarWindow.isEmpty()) return
        val months = affectedDates
            .mapNotNull { runCatching { YearMonth.from(LocalDate.parse(it)) }.getOrNull() }
            .flatMap { listOf(it.minusMonths(1), it, it.plusMonths(1)) }
            .toSet()
        calendarSnapshots = if (months.isEmpty()) emptyMap() else calendarSnapshots - months
    }

    fun selectTodayDate(date: String) {
        selectedTodayDate = date
        if (overlays.lastOrNull() !is Overlay.DayDetail) pushOverlay(Overlay.DayDetail(date))
        load {
            selectedDaySnapshot = repository.loadDayWorkoutSnapshot(date)
            loadDayCoachPreview()
        }
    }

    /**
     * Engine prescriptions for the selected day's recommended template,
     * computed without opening the session — the "here's what the coach has
     * planned" block in the day detail. Null when the day has no scheduled
     * template (or it was already completed).
     */
    var dayCoachPreview by mutableStateOf<List<CoachPreviewItem>?>(null)
        private set

    private fun loadDayCoachPreview() {
        dayCoachPreview = coachPreviewFor(selectedDaySnapshot)
    }

    /**
     * Engine prescriptions for a day's recommended template, computed without
     * opening the session. Null when the day has no engine-driven template or
     * was already trained. Shared by the Today edict and the day-detail sheet.
     */
    private fun coachPreviewFor(snapshot: DayWorkoutSnapshot): List<CoachPreviewItem>? {
        val recommendation = snapshot.recommendation
        val templateId = recommendation?.templateId
        if (recommendation == null || templateId == null || !recommendation.useEngine ||
            !repository.isCoachEnabled() || snapshot.completedWorkouts.isNotEmpty()
        ) {
            return null
        }
        val template = plannerSnapshot.templates.firstOrNull { it.id == templateId } ?: return null
        val session = repository.buildSessionFromTemplate(template, recommendation.date)
        return session.items.filter { it.title.isNotBlank() }.map { item ->
            CoachPreviewItem(
                title = item.title,
                prescription = buildString {
                    when {
                        item.targetDistanceKm != null || item.progressionRole == ProgressionRole.RUNNING -> {
                            item.targetDistanceKm?.let { append(formatDistance(it)) }
                            item.targetPaceSecondsPerKm?.let {
                                if (isNotEmpty()) append(" @ ")
                                append(formatPace(it.toDouble()))
                            }
                        }
                        else -> {
                            item.plannedSets?.let { append("$it × ") }
                            append(item.targetReps.ifBlank { "—" })
                            item.targetWeight?.let { append("  @ ${formatWeight(it)}") }
                        }
                    }
                },
                note = item.coachNote,
            )
        }
    }



    fun canEditScheduleForSelectedDate(): Boolean =
        runCatching { LocalDate.parse(selectedTodayDate) }.getOrNull()?.isAfter(LocalDate.now().minusDays(1)) == true

    // (assignSelectedDateTemplate was deleted 2026-08-30: no UI ever called it,
    // and it carried its own — start-anchored, wrong — copy of the date→slot
    // arithmetic. Scheduling edits go through the plan editor or the ingest.)

    // Two distinct removals, two distinct intents — long-pressing the plan card
    // must never delete the draft that happens to share the day (audit #28).

    /** Delete the unfinished draft on the selected day (long-press its card). */
    fun deleteSelectedDateDraft() {
        load {
            val draft = repository.getDraftSessions().firstOrNull { it.workoutDate == selectedTodayDate }
            if (draft == null) {
                statusMessage = "Nothing to delete"
                return@load
            }
            repository.deleteSession(draft.id)
            refreshAfterHistoryChange()
            statusMessage = "Draft deleted"
        }
    }

    /** Erase a completed workout from history (long-press its record + confirm). */
    fun deleteCompletedWorkout(workout: WorkoutGroup) {
        load {
            repository.deleteWorkoutGroup(workout.date, workout.workoutName)
            refreshAfterHistoryChange(affectedDates = listOf(workout.date))
            statusMessage = "Removed from history"
        }
    }

    /** Clear the plan's slot for the selected day (long-press the plan card). */
    fun clearSelectedDatePlanSlot() {
        load {
            val slot = planSlotForDate(selectedTodayDate)
            if (slot == null) {
                statusMessage = "Nothing to remove"
            } else {
                val updatedPlan = slot.first.copy(
                    days = slot.first.days.map { day ->
                        if (day.weekIndex == slot.second.weekIndex && day.dayIndex == slot.second.dayIndex) {
                            day.copy(
                                dayType = PlanDayType.REST,
                                templateId = null,
                                templateName = "",
                                customWorkoutName = "",
                            )
                        } else day
                    },
                )
                repository.saveWorkoutPlan(updatedPlan)
                refreshPlanDrivenState(updatedPlan.id)
                statusMessage = "Workout removed from plan"
            }
        }
    }

    fun updateCurrentSession(transform: (WorkoutSession) -> WorkoutSession) {
        val session = currentSession ?: return
        currentSession = transform(session)
    }

    fun addSessionExercise(name: String) {
        if (currentSession == null || name.isBlank()) return
        load {
            // A muscle-group name ("Chest") is a slot: the engine fills it with
            // the pool exercise done least recently, for built-in variety.
            val resolved = repository.resolveMuscleGroupSlot(name, ProgressionRole.ACCESSORY)
            val exerciseName = resolved?.first ?: name
            // The exercise's role comes from the muscle-rule pools — Incline
            // Bench Press sitting in a PRIMARY pool arrives as a primary lift.
            val role = repository.roleForExercise(exerciseName)
            // Ad-hoc additions get the same engine prescription as planned ones.
            val prescription = repository.getLiftPrescriptionForExercise(exerciseName, role)
            val coachNote = listOfNotNull(resolved?.second, prescription?.rationale).joinToString(" ")
            val session = currentSession ?: return@load
            currentSession = session.copy(
                items = (session.items + WorkoutSessionItem(
                    id = nextGeneratedId(),
                    exerciseName = exerciseName,
                    progressionRole = role,
                    plannedSets = prescription?.sets,
                    targetReps = prescription?.reps?.toString().orEmpty(),
                    targetWeight = prescription?.weightKg,
                    coachNote = coachNote,
                    hints = repository.getHintForExercise(exerciseName),
                )).normalizeSessionItems(),
            )
        }
    }

    fun updateSessionItem(itemId: Long, transform: (WorkoutSessionItem) -> WorkoutSessionItem) {
        val session = currentSession ?: return
        currentSession = session.copy(
            items = session.items.map { item ->
                if (item.id == itemId) transform(item) else item
            }.normalizeSessionItems(),
        )
    }

    fun removeSessionItem(itemId: Long) {
        val session = currentSession ?: return
        currentSession = session.copy(
            items = session.items.filterNot { it.id == itemId }.normalizeSessionItems(),
        )
    }

    /**
     * Append one fully-specified set to an exercise — what the focus-mode
     * stage calls when you press "Log set": it dials values into a transient
     * draft, then commits them here in a single step (no add-then-edit dance).
     */
    fun commitLoggedSet(itemId: Long, apply: (WorkoutSessionSet) -> WorkoutSessionSet) {
        val session = currentSession ?: return
        if (session.workoutDate > today()) {
            val prettyDate = runCatching {
                LocalDate.parse(session.workoutDate).format(DateTimeFormatter.ofPattern("d MMM"))
            }.getOrDefault(session.workoutDate)
            statusMessage = "Scheduled for $prettyDate — log it on the day"
            return
        }
        currentSession = session.copy(
            items = session.items.map { item ->
                if (item.id != itemId) return@map item
                val newSet = apply(
                    WorkoutSessionSet(id = nextGeneratedId(), setIndex = item.loggedSets.size + 1),
                ).copy(loggedAtMillis = System.currentTimeMillis())
                // A note recorded while on this set becomes this set's note (R20).
                val withNote = newSet.copy(comment = joinComments(newSet.comment, item.pendingComment))
                item.copy(loggedSets = (item.loggedSets + withNote).normalizeSessionSets(), pendingComment = "")
            }.normalizeSessionItems(),
        )
    }

    /**
     * A spoken (or typed) note for the set he is on — it waits on the item and
     * moves onto that set when he logs it. Several notes on one set join up.
     */
    fun addSetNote(itemId: Long, text: String) {
        val note = text.trim()
        if (note.isEmpty()) return
        val session = currentSession ?: return
        val item = session.items.firstOrNull { it.id == itemId } ?: return
        updateSessionItem(itemId) { it.copy(pendingComment = joinComments(it.pendingComment, note)) }
        val planned = item.plannedSets
        val logged = item.loggedSets.size
        statusMessage = if (planned != null && logged >= planned && logged > 0) {
            "Note kept · after set $logged"
        } else {
            "Note kept · set ${logged + 1}"
        }
    }

    /** Replace (or with blank text, delete) the note waiting for the current set. */
    fun setPendingNote(itemId: Long, text: String) {
        updateSessionItem(itemId) { it.copy(pendingComment = text.trim()) }
    }

    /** Replace (or with blank text, delete) a logged set's note. */
    fun setLoggedSetNote(itemId: Long, setId: Long, text: String) {
        updateSessionItem(itemId) { item ->
            item.copy(loggedSets = item.loggedSets.map { set -> if (set.id == setId) set.copy(comment = text.trim()) else set })
        }
    }

    private var lastRemovedSet: Pair<Long, WorkoutSessionSet>? = null

    fun removeLoggedSet(itemId: Long, setId: Long) {
        val session = currentSession ?: return
        val removed = session.items.firstOrNull { it.id == itemId }?.loggedSets?.firstOrNull { it.id == setId }
        currentSession = session.copy(
            items = session.items.map { item ->
                if (item.id != itemId) return@map item
                item.copy(loggedSets = item.loggedSets.filterNot { it.id == setId }.normalizeSessionSets())
            }.normalizeSessionItems(),
        )
        // A ledger ✕ is one mis-tap from erasing a committed set — offer the way
        // back on the toast instead of interrupting with a confirm dialog.
        if (removed != null) {
            lastRemovedSet = itemId to removed
            statusMessage = "Set removed"
            statusActionLabel = "UNDO"
            statusAction = { undoRemoveLoggedSet() }
        }
    }

    /** Reinstate the last ✕-removed set, original values and timestamp intact. */
    private fun undoRemoveLoggedSet() {
        val (itemId, set) = lastRemovedSet ?: return
        lastRemovedSet = null
        val session = currentSession ?: return
        currentSession = session.copy(
            items = session.items.map { item ->
                if (item.id != itemId) return@map item
                item.copy(loggedSets = (item.loggedSets + set).normalizeSessionSets())
            }.normalizeSessionItems(),
        )
        statusMessage = "Set restored"
        statusActionLabel = null
        statusAction = null
    }

    /** True while a completion is in flight — one seal press, one history flush. */
    private var completingSession = false

    fun completeCurrentSession() {
        val session = currentSession ?: return
        if (!canCompleteCurrentSession()) {
            statusMessage = if (session.items.none { it.loggedSets.isNotEmpty() }) {
                "Log at least one set before sealing"
            } else {
                "Future workouts can only be saved, not completed"
            }
            return
        }
        if (completingSession) return
        completingSession = true
        // Clear the live session SYNCHRONOUSLY, before dispatching to IO. It used
        // to be nulled inside the load{} block below, which left a window of one
        // whole DB round-trip in which stashSession() (reached from the seal
        // handler's onBack() on the very next line) still saw a non-null session
        // with id == 0L and inserted it a second time as a draft. `session` is
        // already captured in a local above, so nothing is lost by nulling here.
        currentSession = null
        clearSessionScratch()
        // Exercises with nothing logged don't reach history — say so rather
        // than letting them vanish silently.
        val dropped = session.items.count { it.loggedSets.isEmpty() }
        load {
            val normalizedSession = sessionWriteMutex.withLock {
                try {
                    // Adopt any id an in-flight autosave minted for this same
                    // session, so the seal UPDATEs that row instead of INSERTing
                    // a twin beside it.
                    val normalized = withAdoptedId(session).withDefaultName()
                    repository.completeSession(normalized.copy(status = SessionStatus.DRAFT))
                    normalized
                } catch (error: Exception) {
                    completingSession = false
                    restoreSessionAfterWriteFailure(session, "Seal", error)
                    null
                } finally {
                    completingSession = false
                }
            } ?: return@load
            refreshAfterHistoryChange(affectedDates = listOf(normalizedSession.workoutDate))
            statusMessage = if (dropped > 0) {
                "Workout completed · $dropped unlogged ${if (dropped == 1) "exercise" else "exercises"} left out"
            } else {
                "Workout completed"
            }
        }
    }

    /** Manual "Back up now" — runs the auto-export and reports the outcome. */
    fun backupNow() {
        load {
            repository.autoExportDatabaseIfConfigured()
            configSnapshot = repository.loadConfigSnapshot()
            val status = configSnapshot.lastBackup
            statusMessage = when {
                configSnapshot.autoBackupFolderLabel.isBlank() -> "Choose a backup folder first"
                status == null -> "Backup did not run"
                status.ok -> "Backed up · ${status.detail}"
                else -> "Backup failed: ${status.detail}"
            }
        }
    }

    fun canCompleteCurrentSession(): Boolean {
        val session = currentSession ?: return false
        val dateOk = session.workoutDate
            .let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.let { !it.isAfter(LocalDate.now()) }
            ?: false
        // An empty workout has nothing to seal — history stays honest.
        return dateOk && session.items.any { it.loggedSets.isNotEmpty() }
    }

    /**
     * Save a completed workout straight into the templates so it can be
     * assigned to plan days. Lìliàng's curated scope has no item-level template
     * editor — the workout is captured as-is and re-prescribed by the engine
     * whenever it's run.
     */
    fun saveWorkoutGroupAsTemplate(workout: WorkoutGroup) {
        load {
            val template = WorkoutTemplate(
                name = workout.displayName,
                discipline = workout.entries.firstOrNull()?.discipline.orEmpty(),
                items = repository.buildTemplateItemsFromWorkout(workout).normalizeTemplateItems(),
            )
            repository.saveTemplate(template)
            plannerSnapshot = repository.loadPlannerSnapshot()
            statusMessage = "Saved as workout"
        }
    }

    fun loadExerciseEditor(item: ExerciseCatalogItem?) {
        exerciseEditor = item ?: newExerciseEditor()
    }

    fun updateExerciseEditor(transform: (ExerciseCatalogItem) -> ExerciseCatalogItem) {
        exerciseEditor = transform(exerciseEditor)
    }

    fun saveExerciseEditor() {
        load {
            repository.saveExercise(exerciseEditor)
            configSnapshot = repository.loadConfigSnapshot()
            dashboardSnapshot = repository.loadDashboard("", dashboardSnapshot.selectedExerciseSummary?.exerciseName)
            exerciseEditor = newExerciseEditor()
            popThrough(Overlay.ExerciseEditor)
            statusMessage = "Exercise saved"
        }
    }

    fun deleteExercise(name: String) {
        load {
            repository.deleteExercise(name)
            configSnapshot = repository.loadConfigSnapshot()
            dashboardSnapshot = repository.loadDashboard("", dashboardSnapshot.selectedExerciseSummary?.exerciseName)
            if (exerciseEditor.name == name) exerciseEditor = newExerciseEditor()
            statusMessage = "Exercise deleted"
        }
    }

    fun loadComboEditor(item: ComboItem?) {
        comboEditor = item ?: ComboItem(name = "", discipline = "", notes = "")
    }

    fun updateComboEditor(transform: (ComboItem) -> ComboItem) {
        comboEditor = transform(comboEditor)
    }

    fun saveComboEditor() {
        load {
            repository.saveCombo(comboEditor)
            configSnapshot = repository.loadConfigSnapshot()
            comboEditor = ComboItem(name = "", discipline = "", notes = "")
            popThrough(Overlay.ComboEditor)
            statusMessage = "Combo saved"
        }
    }

    fun deleteCombo(id: Long) {
        load {
            repository.deleteCombo(id)
            configSnapshot = repository.loadConfigSnapshot()
            if (comboEditor.id == id) comboEditor = ComboItem(name = "", discipline = "", notes = "")
            statusMessage = "Combo deleted"
        }
    }

    fun loadMuscleGroupRuleEditor(item: MuscleGroupRule?) {
        muscleGroupRuleEditor = item ?: MuscleGroupRule()
    }

    fun updateMuscleGroupRuleEditor(transform: (MuscleGroupRule) -> MuscleGroupRule) {
        muscleGroupRuleEditor = transform(muscleGroupRuleEditor)
    }

    fun selectMuscleGroupPoolMuscle(muscleGroup: String) {
        muscleGroupRuleEditor = muscleGroupRuleEditor.copy(muscleGroup = muscleGroup)
        ensureCurrentMuscleGroupPoolSeeded()
    }

    fun selectMuscleGroupPoolRole(role: ProgressionRole) {
        muscleGroupRuleEditor = muscleGroupRuleEditor.copy(role = role)
        ensureCurrentMuscleGroupPoolSeeded()
    }

    fun ensureCurrentMuscleGroupPoolSeeded() {
        val current = muscleGroupRuleEditor
        if (current.muscleGroup.isBlank()) return
        load {
            repository.ensureMuscleGroupPoolSeeded(current.muscleGroup, current.role)
            configSnapshot = repository.loadConfigSnapshot()
        }
    }

    fun addExerciseToCurrentMuscleGroupPool(exerciseName: String) {
        val current = muscleGroupRuleEditor
        if (current.muscleGroup.isBlank() || exerciseName.isBlank()) return
        load {
            repository.addExerciseToMuscleGroupPool(current.muscleGroup, current.role, exerciseName)
            configSnapshot = repository.loadConfigSnapshot()
            statusMessage = "Pool updated"
        }
    }

    fun saveMuscleGroupRuleEditor() {
        load {
            repository.saveMuscleGroupRule(muscleGroupRuleEditor)
            configSnapshot = repository.loadConfigSnapshot()
            muscleGroupRuleEditor = MuscleGroupRule()
            statusMessage = "Muscle rule saved"
        }
    }

    fun deleteMuscleGroupRule(id: Long) {
        load {
            repository.deleteMuscleGroupRule(id)
            configSnapshot = repository.loadConfigSnapshot()
            if (muscleGroupRuleEditor.id == id) muscleGroupRuleEditor = MuscleGroupRule()
            statusMessage = "Muscle rule deleted"
        }
    }

    fun loadPlanEditor(plan: WorkoutPlan?) {
        planEditor = plan ?: newPlanEditor()
        plannerWeekIndex = currentPlanWeek(planEditor)
        pushOverlay(Overlay.PlanEditor)
    }

    // ───────────────────────── Martial-arts form tracking ─────────────────────────

    /** Hall snapshot: forms, disciplines, stats, recent sessions, checkpoints. */
    var martialArtsHall by mutableStateOf(MartialArtsHallSnapshot())
        private set

    /** The art whose page is open in the hall; null = the hall overview. */
    var hallDiscipline by mutableStateOf<String?>(null)
        private set

    fun openHallDiscipline(name: String) {
        hallDiscipline = name
    }

    fun closeHallDiscipline() {
        hallDiscipline = null
    }

    /** The runtime forms catalogue (DB-backed, user-editable). */
    val martialArtsForms: List<MartialArtsForm>
        get() = martialArtsHall.forms

    /** Refresh the forms catalogue, per-form progress, and the hall stats. */
    fun refreshMartialArtsForms() {
        load {
            val hall = repository.loadMartialArtsHall()
            martialArtsHall = hall
            martialArtsFormProgress = hall.forms.associate {
                it.id to repository.loadMartialArtsFormProgress(it.id)
            }
        }
    }

    /**
     * Toggle the learned state of a single move. Optimistically updates the
     * in-memory progress map, persists via the repository, and refreshes from
     * the source of truth on the IO dispatcher to pick up the canonical
     * timestamp the DB just wrote.
     */
    fun toggleMartialArtsMoveLearned(formId: String, moveIndex: Int) {
        val currentForm = martialArtsFormProgress[formId]
            ?: MartialArtsFormProgress(formId = formId, totalMoves = martialArtsForms.firstOrNull { it.id == formId }?.moveCount ?: 0)
        val nowLearned = !(currentForm.progressByIndex[moveIndex]?.isLearned ?: false)
        load {
            repository.setMartialArtsMoveLearned(formId, moveIndex, nowLearned)
            val refreshed = repository.loadMartialArtsFormProgress(formId)
            // Merge on Main — two quick toggles on different forms could
            // interleave IO-side and revert one of them visually.
            withContext(Dispatchers.Main) {
                martialArtsFormProgress = martialArtsFormProgress + (formId to refreshed)
            }
        }
    }

    /** Save (create or edit) a form, then refresh the hall. */
    fun saveMartialArtsForm(form: MartialArtsForm) {
        load {
            try {
                repository.saveMartialArtsForm(form)
            } catch (error: IllegalStateException) {
                statusMessage = error.message
                return@load
            }
            refreshMartialArtsForms()
            statusMessage = "Form saved"
        }
    }

    fun deleteMartialArtsForm(formId: String) {
        load {
            repository.deleteMartialArtsForm(formId)
            refreshMartialArtsForms()
            statusMessage = "Form deleted"
        }
    }

    fun setFormMastered(formId: String, mastered: Boolean) {
        load {
            repository.setFormMastered(formId, mastered)
            refreshMartialArtsForms()
            statusMessage = if (mastered) "Form mastered 🥋" else "Form reopened"
            if (mastered) checkSeals()
        }
    }

    // ───────────────────────── 印谱 — the seal album (R22) ─────────────

    /** Seal id → the day it was earned. */
    var sealAwards by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Seals earned but not yet shown with the stamp-down, oldest first. */
    var sealsToCelebrate by mutableStateOf<List<String>>(emptyList())
        private set

    fun openSealAlbum() = pushOverlay(Overlay.SealAlbum)

    /** Today's reading of everything the seals measure. */
    fun currentSealStats(): com.liliang.app.data.SealStats {
        val kpis = dashboardSnapshot.kpis
        val (trainedDays, last30, harmonies) = com.liliang.app.data.SealStatsBuilder.attendance(
            kpis.attendance.dailyHistory,
            kpis.attendance.dailyActivity,
        )
        val streak = com.liliang.app.ui.computeForgivingStreak(
            kpis.attendance.dailyHistory,
            com.liliang.app.ui.planRestWeekdays(configSnapshot.plans),
        )
        val heaviest = kpis.featuredLifts.maxOfOrNull { maxOf(it.maxSetWeight ?: 0.0, it.maxWeight ?: 0.0) } ?: 0.0
        return com.liliang.app.data.SealStats(
            trainedDays = trainedDays,
            trainedDaysThisYear = kpis.attendance.thisYear,
            trainedLast30 = last30,
            streak = streak,
            prCount = kpis.recentPrs.size,
            heaviestLiftKg = heaviest,
            longestRunKm = kpis.running.longestRunKm,
            runKmThisYear = kpis.running.thisYearDistanceKm,
            martialSessionsThisYear = kpis.martialArts.totalThisYearSessions,
            formsMastered = martialArtsForms.count { it.isMastered },
            movesLearned = martialArtsFormProgress.values.sumOf { it.learnedCount },
            chambersPassed = chamberPasses.size,
            threeHarmonies = harmonies,
        )
    }

    fun sealStates(): List<com.liliang.app.data.SealState> =
        com.liliang.app.data.SealCatalog.evaluate(currentSealStats(), sealAwards)

    /**
     * Award any seal the history now meets, and queue it for the stamp-down.
     * Cheap: reads two small tables and the snapshots already in memory, so it
     * runs after every dashboard refresh and after Hall/Chamber changes.
     */
    fun checkSeals() {
        load {
            if (chamberPasses.isEmpty()) chamberPasses = repository.loadChamberPasses()
            if (martialArtsHall.forms.isEmpty()) {
                val hall = repository.loadMartialArtsHall()
                martialArtsHall = hall
                martialArtsFormProgress = hall.forms.associate { it.id to repository.loadMartialArtsFormProgress(it.id) }
            }
            val awards = repository.loadSealAwards()
            val fresh = com.liliang.app.data.SealCatalog.newlyEarned(currentSealStats(), awards)
            if (fresh.isNotEmpty()) {
                repository.awardSeals(fresh.map { it.id })
                withContext(Dispatchers.Main) {
                    sealsToCelebrate = sealsToCelebrate + fresh.map { it.id }
                }
            }
            sealAwards = repository.loadSealAwards()
        }
    }

    /** The front seal has been shown; move on to the next one, if any. */
    fun consumeSealCelebration() {
        sealsToCelebrate = sealsToCelebrate.drop(1)
    }

    // ───────────────────────── 三十六房 — the 36 Chambers (R21) ─────────────

    /** Chamber number → the day it was passed. */
    var chamberPasses by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    /** Chamber number → progress toward its measurable target. */
    var chamberProgress by mutableStateOf<Map<Int, ChamberProgress>>(emptyMap())
        private set

    fun refreshChambers() {
        load {
            chamberPasses = repository.loadChamberPasses()
            chamberProgress = ChamberCatalog.chambers.associate { it.number to repository.chamberProgress(it) }
        }
    }

    fun setChamberPassed(number: Int, passed: Boolean) {
        load {
            repository.setChamberPassed(number, passed)
            chamberPasses = repository.loadChamberPasses()
            statusMessage = if (passed) "第${chineseNumeral(number)}房 — yours" else "Chamber reopened"
            if (passed) checkSeals()
        }
    }

    /** Where you got to in [formId] last time (latest checkpoint), so the move picker opens there. */
    fun latestCheckpointMove(formId: String): Int? =
        martialArtsHall.checkpointsByForm[formId]
            ?.maxWithOrNull(compareBy({ it.date }, { it.id }))
            ?.moveIndex
            ?.takeIf { it > 0 }

    /** Add or update a discipline (the Kendo case), then refresh the hall. */
    fun saveMartialArtsDiscipline(discipline: MartialArtsDiscipline) {
        load {
            try {
                repository.saveMartialArtsDiscipline(discipline)
            } catch (error: IllegalStateException) {
                statusMessage = error.message
                return@load
            }
            configSnapshot = repository.loadConfigSnapshot()
            refreshMartialArtsForms()
            statusMessage = "Discipline added"
        }
    }

    fun updatePlanEditor(transform: (WorkoutPlan) -> WorkoutPlan) {
        planEditor = transform(planEditor)
    }

    fun savePlanEditor() {
        val plan = planEditor
        if (plan.name.isBlank()) {
            statusMessage = "Plan name required"
            return
        }
        if (runCatching { LocalDate.parse(plan.startDate.trim()) }.getOrNull() == null) {
            statusMessage = "Start date must be a real date (YYYY-MM-DD)"
            return
        }
        load {
            val savedId = repository.saveWorkoutPlan(plan)
            configSnapshot = repository.loadConfigSnapshot()
            planSettingsEditor = configSnapshot.planSettings
            // The calendar + Today paint plan days — a saved plan must show up
            // everywhere without an app restart.
            refreshAfterHistoryChange()
            planEditor = configSnapshot.plans.firstOrNull { it.id == savedId } ?: newPlanEditor()
            popThrough(Overlay.PlanEditor)
            statusMessage = "Plan saved"
        }
    }

    fun deletePlan(planId: Long) {
        load {
            repository.deleteWorkoutPlan(planId)
            // The full plan-driven refresh — a partial one here left the dead
            // plan's tints on cached calendar months and its workout on the
            // Today plaque until a tab switch.
            refreshPlanDrivenState()
            planSettingsEditor = configSnapshot.planSettings
            if (planEditor.id == planId) planEditor = newPlanEditor()
            popThrough(Overlay.PlanEditor)
            statusMessage = "Plan deleted"
        }
    }

    fun updatePlanDay(weekIndex: Int, dayIndex: Int, transform: (WorkoutPlanDay) -> WorkoutPlanDay) {
        planEditor = planEditor.copy(
            days = planEditor.days.map { day ->
                if (day.weekIndex == weekIndex && day.dayIndex == dayIndex) transform(day) else day
            },
        )
    }

    fun setPlanWeeks(weeks: Int) {
        val normalizedWeeks = weeks.coerceIn(1, 12)
        planEditor = planEditor.copy(
            weeks = normalizedWeeks,
            days = buildPlanDays(normalizedWeeks, planEditor.days),
        )
        plannerWeekIndex = plannerWeekIndex.coerceIn(0, normalizedWeeks - 1)
    }

    fun shiftPlannerWeek(step: Int) {
        plannerWeekIndex = (plannerWeekIndex + step).coerceIn(0, (planEditor.weeks - 1).coerceAtLeast(0))
    }

    fun movePlanDay(weekIndex: Int, dayIndex: Int, direction: Int) {
        val targetDayIndex = (dayIndex + direction).coerceIn(0, 6)
        if (targetDayIndex == dayIndex) return
        movePlanDayTo(weekIndex, dayIndex, targetDayIndex)
    }

    fun movePlanDayTo(weekIndex: Int, fromDayIndex: Int, toDayIndex: Int) {
        if (fromDayIndex == toDayIndex) return
        val current = planEditor.days.firstOrNull { it.weekIndex == weekIndex && it.dayIndex == fromDayIndex } ?: return
        val target = planEditor.days.firstOrNull { it.weekIndex == weekIndex && it.dayIndex == toDayIndex } ?: return
        planEditor = planEditor.copy(
            days = planEditor.days.map { day ->
                when {
                    day.id == current.id -> current.copy(
                        dayType = target.dayType,
                        templateId = target.templateId,
                        templateName = target.templateName,
                        customWorkoutName = target.customWorkoutName,
                    )
                    day.id == target.id -> target.copy(
                        dayType = current.dayType,
                        templateId = current.templateId,
                        templateName = current.templateName,
                        customWorkoutName = current.customWorkoutName,
                    )
                    else -> day
                }
            },
        )
    }

    fun updatePlanSettingsEditor(transform: (WorkoutPlanSettings) -> WorkoutPlanSettings) {
        planSettingsEditor = transform(planSettingsEditor)
    }

    fun savePlanSettingsEditor() {
        load {
            repository.saveWorkoutPlanSettings(planSettingsEditor)
            configSnapshot = repository.loadConfigSnapshot()
            planSettingsEditor = configSnapshot.planSettings
            popThrough(Overlay.EngineSettings)
            statusMessage = "Plan settings saved"
        }
    }

    fun saveDashboardFeaturedExercise(index: Int, name: String) {
        load {
            val current = configSnapshot.dashboardFeaturedExercises.ifEmpty {
                listOf("Incline Bench", "Viking Press", "Leg Press")
            }.toMutableList()
            while (current.size < 3) current += ""
            current[index] = name
            repository.saveDashboardFeaturedExercises(current)
            configSnapshot = repository.loadConfigSnapshot()
            refreshDashboard(selectedExercise = dashboardSnapshot.selectedExerciseSummary?.exerciseName)
            statusMessage = "Dashboard updated"
        }
    }

    fun exportDatabase(uri: Uri) {
        load {
            repository.exportDatabase(uri)
            statusMessage = "Database exported"
        }
    }

    fun importDatabase(uri: Uri, target: ImportTarget = ImportTarget.FULL_DATABASE) {
        load {
            repository.importTarget(uri, target)
            // Everything derived from history is now stale — refresh it all.
            currentSession = null
            clearSessionScratch()
            configSnapshot = repository.loadConfigSnapshot()
            planSettingsEditor = configSnapshot.planSettings
            plannerTemplate = repository.emptyTemplate()
            exerciseEditor = newExerciseEditor()
            comboEditor = ComboItem(name = "", discipline = "", notes = "")
            refreshAfterHistoryChange()
            refreshMartialArtsForms()
            statusMessage = "${target.label} imported"
        }
    }

    fun setAutoBackupFolder(uri: Uri?) {
        load {
            repository.setAutoBackupFolder(uri)
            configSnapshot = repository.loadConfigSnapshot()
            repository.autoExportDatabaseIfConfigured()
            statusMessage = if (uri == null) "Auto backup cleared" else "Auto backup set"
        }
    }

    fun setIngestFolder(uri: Uri?) {
        load {
            repository.setIngestFolder(uri)
            if (uri == null) {
                configSnapshot = repository.loadConfigSnapshot()
                statusMessage = "Ingest folder cleared"
            } else {
                // A newly chosen folder should show its contents immediately.
                val result = repository.runFolderIngest(force = true)
                configSnapshot = repository.loadConfigSnapshot()
                statusMessage = "Ingest folder set · ${result.detail}"
                if (result.applied > 0) refreshAll()
            }
        }
    }

    fun runIngestNow() {
        load {
            val result = repository.runFolderIngest(force = true)
            configSnapshot = repository.loadConfigSnapshot()
            statusMessage = "Ingest · ${result.detail}"
            if (result.applied > 0) refreshAll()
        }
    }

    /**
     * Silent launch-time pass: unchanged files cost one hash each and no UI
     * noise; real changes refresh the world and say what arrived. Failures are
     * recorded in Settings (lastIngest) rather than surfaced as a startup error.
     */
    private fun autoIngestOnLaunch() {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.runFolderIngest() }
            }.getOrNull() ?: return@launch
            if (result.applied > 0) {
                refreshAll()
                statusMessage = "Folder ingest · ${result.detail}"
            }
        }
    }

    private fun newExerciseEditor(): ExerciseCatalogItem = ExerciseCatalogItem(
        name = "",
        defaultMuscle = "",
        defaultCategory = "Hypertrophy",
        logFormat = LogFormat.REPS,
        isBodyweight = false,
    )

    private fun newPlanEditor(): WorkoutPlan = WorkoutPlan(
        name = "",
        startDate = today(),
        weeks = 1,
        days = buildPlanDays(1),
    )

    private fun ensurePlannerPlanLoaded() {
        val active = configSnapshot.plans.firstOrNull { it.isActive } ?: configSnapshot.plans.firstOrNull()
        if (active != null && planEditor.id != active.id) {
            planEditor = active
        }
        plannerWeekIndex = currentPlanWeek(planEditor)
    }

    /**
     * The one refresh for any mutation that changes history or schedule —
     * completion, draft save/delete, quick log, plan change, import. Every
     * surface derived from the database is rebuilt here. Mutation paths must
     * NOT hand-roll their own subset: six drifting copies of this block are
     * exactly how the Today room went stale (audit R6-2).
     */
    private suspend fun refreshAfterHistoryChange(affectedDates: Collection<String> = emptyList()) {
        draftSessions = repository.getDraftSessions()
        todayPlanRecommendation = repository.getTodayPlanRecommendation()
        invalidateCalendarWindow(affectedDates)
        selectedDaySnapshot = repository.loadDayWorkoutSnapshot(selectedTodayDate)
        // The coach preview hangs off the selected-day snapshot — without this
        // a sealed workout's day sheet kept showing "COACH'S PLAN" for the
        // workout just done.
        loadDayCoachPreview()
        todaySnapshot = repository.loadDayWorkoutSnapshot(today())
        dashboardSnapshot = repository.loadDashboard("", dashboardSnapshot.selectedExerciseSummary?.exerciseName)
        plannerSnapshot = repository.loadPlannerSnapshot()
        goalProgressList = repository.getGoalProgressList()
    }

    private suspend fun refreshPlanDrivenState(preferredPlanId: Long? = null) {
        configSnapshot = repository.loadConfigSnapshot()
        refreshAfterHistoryChange()
        preferredPlanId?.let { id ->
            planEditor = configSnapshot.plans.firstOrNull { it.id == id } ?: planEditor
            plannerWeekIndex = currentPlanWeek(planEditor)
        }
    }

    private fun planSlotForDate(date: String): Pair<WorkoutPlan, WorkoutPlanDay>? {
        val plan = configSnapshot.plans.firstOrNull { it.isActive } ?: return null
        val target = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        if (target.isBefore(LocalDate.now())) return null
        // Shared Monday-anchored mapper — this used to re-derive the slot from
        // the plan's start weekday (wrong weekday on non-Monday plans, dead on
        // rolling plans past week one).
        val day = planSlotFor(plan, target) ?: return null
        return plan to day
    }

    /**
     * Run [block] off the main thread. Extracted into its own suspend helper so
     * the @Suppress annotation that silences the spurious Kotlin compiler
     * "expression is unused" warning (fired on the implicit Unit return of a
     * suspend lambda invocation) can target the smallest possible scope.
     */
    @Suppress("UNUSED_EXPRESSION")
    private suspend fun runBlock(block: suspend LiliangViewModel.() -> Unit) {
        withContext(Dispatchers.IO) { block() }
    }

    /** Overlapping loads used to race the boolean — the first finisher hid the
     *  ink hairline while three others still ran. Count instead. */
    private var activeLoadCount = 0

    private fun load(block: suspend LiliangViewModel.() -> Unit) {
        viewModelScope.launch {
            activeLoadCount++
            isBusy = true
            try {
                // Run all repository / DB work off the main thread to avoid ANRs.
                // Compose snapshot state (mutableStateOf) is safe to mutate from any thread —
                // recomposition is dispatched onto the main thread automatically.
                runBlock(block)
            } catch (cancellation: CancellationException) {
                // Coroutine cancellation is normal when the ViewModel scope is closing or a
                // new load() supersedes a previous one. Don't surface this as an error.
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "ViewModel load operation failed", error)
                statusMessage = error.message ?: "Something went wrong"
            } finally {
                activeLoadCount--
                isBusy = activeLoadCount > 0
            }
        }
    }

    private fun nextGeneratedId(): Long = nextGeneratedIdSeed--

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun currentPlanWeek(plan: WorkoutPlan): Int {
        if (plan.startDate.isBlank()) return 0
        val start = runCatching { LocalDate.parse(plan.startDate) }.getOrNull() ?: return 0
        val offset = java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.now()).toInt()
        if (offset < 0) return 0
        return (offset / 7).coerceIn(0, (plan.weeks - 1).coerceAtLeast(0))
    }

    companion object {
        private const val TAG = "LiliangViewModel"

        /**
         * Default production wiring: pulls the [Application] out of [CreationExtras] and
         * builds a real [WorkoutRepository] backed by SQLite. Use this from MainActivity
         * via `viewModel(factory = LiliangViewModel.Factory)`.
         *
         * Tests should not use this factory; they should construct [LiliangViewModel]
         * directly with a fake [Application] and a test [WorkoutRepository] subclass.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "Application is required in CreationExtras to construct LiliangViewModel"
                }
                LiliangViewModel(
                    application = application,
                    repository = WorkoutRepository(application.applicationContext),
                )
            }
        }
    }
}

private fun buildPlanDays(weeks: Int, existing: List<WorkoutPlanDay> = emptyList()): List<WorkoutPlanDay> {
    val existingMap = existing.associateBy { it.weekIndex to it.dayIndex }
    return buildList {
        repeat(weeks) { week ->
            repeat(7) { day ->
                add(
                    existingMap[week to day] ?: WorkoutPlanDay(
                        weekIndex = week,
                        dayIndex = day,
                        dayLabel = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[day],
                    ),
                )
            }
        }
    }
}

private fun WorkoutTemplate.withEditableIds(nextTempId: () -> Long): WorkoutTemplate = copy(
    items = items.map { item ->
        item.copy(id = item.id.takeIf { it != 0L } ?: nextTempId())
    }.normalizeTemplateItems(),
)

private fun WorkoutSession.withEditableIds(nextTempId: () -> Long): WorkoutSession = copy(
    items = items.map { item ->
        item.copy(
            id = item.id.takeIf { it != 0L } ?: nextTempId(),
            loggedSets = item.loggedSets.map { set ->
                set.copy(id = set.id.takeIf { it != 0L } ?: nextTempId())
            }.normalizeSessionSets(),
        )
    }.normalizeSessionItems(),
)

private fun WorkoutSession.withDefaultName(): WorkoutSession =
    if (name.isBlank()) copy(name = "Custom Workout") else this

private fun List<WorkoutTemplateItem>.normalizeTemplateItems(): List<WorkoutTemplateItem> =
    mapIndexed { index, item -> item.copy(sortOrder = index) }

private fun List<WorkoutSessionItem>.normalizeSessionItems(): List<WorkoutSessionItem> =
    mapIndexed { index, item -> item.copy(sortOrder = index, loggedSets = item.loggedSets.normalizeSessionSets()) }

private fun List<WorkoutSessionSet>.normalizeSessionSets(): List<WorkoutSessionSet> =
    mapIndexed { index, set -> set.copy(setIndex = index + 1) }

// ───────────────────────── Heavy-bag rounds ─────────────────────────

/** A planned bag session for one session item: one combo per round. */
data class BagPlan(
    val itemId: Long = 0L,
    val rounds: List<ComboItem> = emptyList(),
    val workSeconds: Int = 180,
    val restSeconds: Int = 60,
    /** Speak each move during the round. Off by default — the combo is
     *  announced before every round, which is what bag work actually needs. */
    val callMoves: Boolean = false,
)

/** Everything the round-timer overlay needs to run and to log honestly. */
data class RoundTimerSetup(
    /** One entry per round, in order — round N runs rounds[N]. */
    val rounds: List<ComboItem>,
    val workSeconds: Int,
    val restSeconds: Int,
    val callMoves: Boolean,
    val origin: RoundTimerOrigin,
)

/** Where a timer run came from — decides how completed rounds are logged. */
sealed interface RoundTimerOrigin {
    /** Rounds log as sets on this session item, each with its round's combo. */
    data class SessionItem(val itemId: Long) : RoundTimerOrigin

    /** Rounds log as one quick martial-arts session for the discipline. */
    data class Hall(val discipline: String) : RoundTimerOrigin
}
