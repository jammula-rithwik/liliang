package com.liliang.app.data

import java.time.LocalDate

/*
 * 印谱 — the seal album.
 *
 * Achievements as carved seals. Each seal is a measurable threshold read
 * from history the app already keeps (attendance, PRs, runs, martial
 * sessions, forms, the 36 Chambers). Once a seal is earned it is written to
 * `seal_awards` with the day it was earned and stays earned, even after the
 * rolling windows that earned it move on.
 *
 * Everything in this file is pure — no Android, no database — so the
 * catalogue and the award rule are unit-tested on the JVM.
 */

/** How rare a seal is; decides its ink. */
enum class SealTier(val hanzi: String, val label: String) {
    /** 朱 cinnabar — the everyday red paste. */
    Cinnabar("朱", "Cinnabar"),

    /** 玉 jade — a step further. */
    Jade("玉", "Jade"),

    /** 金 gold — rare. */
    Gold("金", "Gold"),
}

enum class SealPath(val hanzi: String, val label: String) {
    Discipline("恒", "Discipline"),
    Iron("铁", "Iron"),
    Road("路", "Road"),
    Martial("武", "Martial"),
    Chambers("房", "Chambers"),
}

/**
 * Everything the seals are measured against, gathered in one place. All
 * counts are plain numbers so tests can build one by hand.
 */
data class SealStats(
    /** Distinct days with any training, all the history the dashboard holds. */
    val trainedDays: Int = 0,
    /** Training days this calendar year. */
    val trainedDaysThisYear: Int = 0,
    /** Training days in the last 30 days, today included. */
    val trainedLast30: Int = 0,
    /** The forgiving streak the Today lantern shows. */
    val streak: Int = 0,
    /** Personal records in the recent PR feed. */
    val prCount: Int = 0,
    /** Heaviest single working set across featured lifts, kg. */
    val heaviestLiftKg: Double = 0.0,
    /** Longest single run, km. */
    val longestRunKm: Double = 0.0,
    /** Running distance this year, km. */
    val runKmThisYear: Double = 0.0,
    /** Martial-arts training days this year. */
    val martialSessionsThisYear: Int = 0,
    /** Forms marked mastered. */
    val formsMastered: Int = 0,
    /** Moves marked learned across every form. */
    val movesLearned: Int = 0,
    /** Chambers passed. */
    val chambersPassed: Int = 0,
    /** Lifted, ran and trained a martial art within the last 7 days. */
    val threeHarmonies: Boolean = false,
)

data class SealDef(
    val id: String,
    /** The carved glyph, one character. */
    val hanzi: String,
    /** Chinese name, as it would be written on the seal's box. */
    val chineseName: String,
    val title: String,
    val description: String,
    val tier: SealTier,
    val path: SealPath,
    val target: Double,
    /** Unit for the progress readout ("days", "km"); blank for a yes/no seal. */
    val unit: String = "",
    val measure: (SealStats) -> Double,
)

/** A seal as the album shows it. */
data class SealState(
    val def: SealDef,
    /** yyyy-MM-dd when earned, or null while still carving. */
    val earnedAt: String?,
    val value: Double,
) {
    val earned: Boolean get() = earnedAt != null
    val progress: Float get() = if (earned) 1f else (value / def.target).toFloat().coerceIn(0f, 1f)
}

object SealCatalog {
    val seals: List<SealDef> = listOf(
        // ── 恒 Discipline ──
        SealDef("first-step", "始", "初始", "The First Step",
            "Log your first day of training. Every hall begins with one bow.",
            SealTier.Cinnabar, SealPath.Discipline, 1.0, "day") { it.trainedDays.toDouble() },
        SealDef("seven-lanterns", "七", "七灯", "Seven Lanterns",
            "Keep the lantern lit for 7 days running.",
            SealTier.Cinnabar, SealPath.Discipline, 7.0, "days") { it.streak.toDouble() },
        SealDef("full-moon", "月", "满月", "Full Moon",
            "A 30-day streak: one whole turn of the moon without the lantern going out.",
            SealTier.Jade, SealPath.Discipline, 30.0, "days") { it.streak.toDouble() },
        SealDef("constancy", "恒", "恒心", "Constancy",
            "Train on 20 of the last 30 days.",
            SealTier.Jade, SealPath.Discipline, 20.0, "days") { it.trainedLast30.toDouble() },
        SealDef("hundred-days", "百", "百日", "A Hundred Days",
            "100 training days in one calendar year. The old masters called this 百日筑基, laying the foundation.",
            SealTier.Gold, SealPath.Discipline, 100.0, "days") { it.trainedDaysThisYear.toDouble() },
        SealDef("three-harmonies", "和", "三合", "Three Harmonies",
            "Lift, run and train a martial art within the same seven days.",
            SealTier.Jade, SealPath.Discipline, 1.0) { if (it.threeHarmonies) 1.0 else 0.0 },

        // ── 铁 Iron ──
        SealDef("breakthrough", "破", "突破", "Breakthrough",
            "Set your first personal record.",
            SealTier.Cinnabar, SealPath.Iron, 1.0, "PR") { it.prCount.toDouble() },
        SealDef("rolling-thunder", "雷", "雷动", "Rolling Thunder",
            "Five personal records in the recent feed at once.",
            SealTier.Jade, SealPath.Iron, 5.0, "PRs") { it.prCount.toDouble() },
        SealDef("mountain", "山", "移山", "Moving Mountains",
            "Lift 100 kg in a single working set.",
            SealTier.Jade, SealPath.Iron, 100.0, "kg") { it.heaviestLiftKg },
        SealDef("mount-tai", "泰", "泰山", "Mount Tai",
            "Lift 140 kg in a single working set, the weight of the eastern peak.",
            SealTier.Gold, SealPath.Iron, 140.0, "kg") { it.heaviestLiftKg },

        // ── 路 Road ──
        SealDef("wind-runner", "风", "追风", "Chasing the Wind",
            "Run 10 km in one go.",
            SealTier.Cinnabar, SealPath.Road, 10.0, "km") { it.longestRunKm },
        SealDef("dragon-run", "龙", "龙行", "Dragon's Road",
            "Run a half marathon, 21.1 km, in one go.",
            SealTier.Gold, SealPath.Road, 21.1, "km") { it.longestRunKm },
        SealDef("thousand-li", "里", "千里", "A Thousand Li",
            "Run 250 km in a calendar year. A journey of a thousand li begins beneath your feet.",
            SealTier.Jade, SealPath.Road, 250.0, "km") { it.runKmThisYear },

        // ── 武 Martial ──
        SealDef("martial-path", "武", "习武", "On the Path",
            "10 martial-arts sessions this year.",
            SealTier.Cinnabar, SealPath.Martial, 10.0, "sessions") { it.martialSessionsThisYear.toDouble() },
        SealDef("iron-fist", "拳", "铁拳", "Iron Fist",
            "50 martial-arts sessions this year.",
            SealTier.Jade, SealPath.Martial, 50.0, "sessions") { it.martialSessionsThisYear.toDouble() },
        SealDef("student", "学", "好学", "The Student",
            "Learn 50 moves across your forms.",
            SealTier.Cinnabar, SealPath.Martial, 50.0, "moves") { it.movesLearned.toDouble() },
        SealDef("form-mastered", "形", "成形", "The Form Complete",
            "Mark a whole form as mastered.",
            SealTier.Jade, SealPath.Martial, 1.0, "form") { it.formsMastered.toDouble() },
        SealDef("lineage", "宗", "传宗", "Keeper of the Lineage",
            "Master three forms.",
            SealTier.Gold, SealPath.Martial, 3.0, "forms") { it.formsMastered.toDouble() },

        // ── 房 Chambers ──
        SealDef("first-chamber", "房", "入室", "Through the Door",
            "Pass your first of the 36 Chambers.",
            SealTier.Cinnabar, SealPath.Chambers, 1.0, "chamber") { it.chambersPassed.toDouble() },
        SealDef("season", "季", "一季", "A Season",
            "Pass three chambers: one season's feats.",
            SealTier.Jade, SealPath.Chambers, 3.0, "chambers") { it.chambersPassed.toDouble() },
        SealDef("thirty-six", "师", "宗师", "Master of the 36th",
            "Pass all 36 Chambers.",
            SealTier.Gold, SealPath.Chambers, 36.0, "chambers") { it.chambersPassed.toDouble() },
    )

    val byId: Map<String, SealDef> = seals.associateBy { it.id }

    /** Every seal with its current reading, in catalogue order. */
    fun evaluate(stats: SealStats, awards: Map<String, String>): List<SealState> =
        seals.map { SealState(it, awards[it.id], it.measure(stats)) }

    /** Seals the stats now meet that have not been awarded yet. */
    fun newlyEarned(stats: SealStats, awards: Map<String, String>): List<SealDef> =
        seals.filter { it.id !in awards && it.measure(stats) >= it.target }
}

/**
 * Builds [SealStats] from the dashboard's daily history. Kept separate from
 * the repository so the date arithmetic is testable.
 */
object SealStatsBuilder {
    fun attendance(
        dailyHistory: Map<String, Int>,
        dailyActivity: Map<String, DayActivity>,
        today: LocalDate = LocalDate.now(),
    ): Triple<Int, Int, Boolean> {
        val trained = dailyHistory.filterValues { it > 0 }.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        val last30 = trained.count { !it.isAfter(today) && it.isAfter(today.minusDays(30)) }
        val week = (0..6).map { today.minusDays(it.toLong()).toString() }
        val acts = week.mapNotNull { dailyActivity[it] }
        val harmonies = acts.any { it.hasLift } && acts.any { it.hasRun } && acts.any { it.hasMartialArts }
        return Triple(trained.size, last30, harmonies)
    }
}
