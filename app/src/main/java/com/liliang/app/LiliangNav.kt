package com.liliang.app

// Lìliàng's navigation model: four rooms reached from the ink dock, and one
// overlay stack above them. v2 tracked sub-screens with scattered booleans
// and a config-destination enum; here a single back stack owns everything —
// one BackHandler, no special cases.

// Dock glyphs render in Ma Shan Zheng, a mainland brush hand that only
// carries simplified forms — hence 历/馆. The brand seal is 力 (力量, Lìliàng).
enum class LiliangTab(val hanzi: String, val label: String) {
    Today("今", "Today"),
    Calendar("历", "Calendar"),
    Scroll("卷", "Scroll"),
    Hall("馆", "Hall"),
}


sealed interface Overlay {
    /** Active workout logging. Content comes from LiliangViewModel.currentSession. */
    data object Session : Overlay

    data class DayDetail(val date: String) : Overlay
    data class ArtPage(val discipline: String) : Overlay
    data class FormTracker(val formId: String) : Overlay
    /** One of the 36 Chambers (R21). */
    data class Chamber(val number: Int) : Overlay
    /** Add/edit a form. Null id = new (optionally seeded with a discipline). */
    data class FormEditor(val formId: String?, val discipline: String = "") : Overlay
    /** Add a martial-arts discipline — the Kendo case. */
    data object DisciplineEditor : Overlay
    data class ExerciseDetail(val name: String) : Overlay

    data object Settings : Overlay
    data object DataBackup : Overlay
    data object Curation : Overlay

    data object Goals : Overlay
    data object GoalEditor : Overlay

    data object Plans : Overlay
    data object PlanEditor : Overlay
    data object EngineSettings : Overlay

    data object Workouts : Overlay
    data object WorkoutEditor : Overlay
    data object ExerciseRoles : Overlay
    /** The forge clock — content comes from LiliangViewModel.roundTimerSetup. */
    data object RoundTimer : Overlay
    /** Quick logging as real pages, not popups. */
    data class RunLog(val date: String) : Overlay
    data class MartialLog(val date: String) : Overlay
    data object Library : Overlay
    data object ExerciseEditor : Overlay
    data object ComboEditor : Overlay
    /** 印谱 — the achievement seal album (R22). */
    data object SealAlbum : Overlay
}
