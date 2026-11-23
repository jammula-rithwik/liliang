package com.liliang.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 三十六房 — the 36 Chambers: a three-year martial path of twelve 13-week
 * seasons, three filmable feats ("chambers") each. This catalogue is a
 * sample path built from the forms Lìliàng ships with; edit it to make it
 * yours. Passing a chamber is the trainee's call (film the trial, then
 * seal it); progress toward one is read from history where a chamber has
 * a measurable target.
 */

/** The six threads a chamber can belong to. Colours are ARGB so the data layer stays Compose-free. */
enum class ChamberPillar(val hanzi: String, val label: String, val argb: Long) {
    FORMS("形", "Forms", 0xFFC73E2E),
    WEAPONS("器", "Weapons", 0xFFB8862B),
    KICKS("腿", "Kicks & flight", 0xFF4F8A6E),
    STRIKING("打", "Striking", 0xFF8A2F3C),
    BODY("身", "Body", 0xFFA56A3A),
    INNER("心", "Inner", 0xFF3F5E78),
}

enum class ChamberMetricKind {
    /** Highest move reached in a form (checkpoints) or learned (tracker), against the form's moves. */
    FORM_MOVES,
    /** Longest single timed hold logged under one of the exercise names. */
    HOLD_SECONDS,
    /** Most rounds of at least [ChamberMetric.minSeconds] logged on one day. */
    ROUNDS,
    /** Most reps in one logged set. */
    REPS,
    /** Longest logged run, in km. */
    DISTANCE_KM,
    /** No number: the trial is judged on film. */
    MANUAL,
}

data class ChamberMetric(
    val kind: ChamberMetricKind = ChamberMetricKind.MANUAL,
    /** The target in the metric's unit. For FORM_MOVES, 0 means "the form's move count × [formFraction]". */
    val target: Double = 0.0,
    val formId: String = "",
    val formFraction: Double = 1.0,
    /** Exercise names; a history row matches the name exactly or as "Name: …" (bag rounds, form rounds). */
    val exercises: List<String> = emptyList(),
    val minSeconds: Int = 0,
)

data class ChamberYear(val number: Int, val hanzi: String, val title: String)

data class ChamberSeason(
    /** Season code: "S2" … "S13". */
    val code: String,
    /** 1…12 along the path. */
    val number: Int,
    val year: Int,
    val hanzi: String,
    val title: String,
    val period: String,
    val start: LocalDate,
) {
    /** Twelve weeks of training, then the gate week. */
    val gateStart: LocalDate get() = start.plusWeeks(12)
    val end: LocalDate get() = start.plusWeeks(13).minusDays(1)

    fun contains(day: LocalDate): Boolean = !day.isBefore(start) && !day.isAfter(end)

    /** Share of the twelve training weeks gone by on [today], 0 before the start and 1 from the gate week on. */
    fun timeFraction(today: LocalDate): Float {
        val days = ChronoUnit.DAYS.between(start, today).toFloat()
        return (days / (12f * 7f)).coerceIn(0f, 1f)
    }

    /** 1…12 in training, 13 in the gate week; 0 before the season starts. */
    fun weekOf(today: LocalDate): Int =
        if (today.isBefore(start)) 0 else (ChronoUnit.DAYS.between(start, today) / 7 + 1).toInt().coerceAtMost(13)
}

data class Chamber(
    val number: Int,
    val seasonCode: String,
    /** The chamber's own name, e.g. 母拳. */
    val hanzi: String,
    val title: String,
    /** The one character painted on its door. */
    val glyph: String,
    val pillar: ChamberPillar,
    /** The trial: the feat that passes it, filmed. */
    val feat: String,
    val gate: String = "",
    val learnFrom: String = "",
    val metric: ChamberMetric = ChamberMetric(),
) {
    val isGated: Boolean get() = gate.isNotBlank()
}

enum class ChamberState { PASSED, CURRENT, UPCOMING, OPEN, LOCKED }

/** Where you stand on a chamber's measurable target. [target] 0 = nothing to measure. */
data class ChamberProgress(
    val value: Double = 0.0,
    val target: Double = 0.0,
    val label: String = "",
) {
    val fraction: Float get() = if (target <= 0.0) 0f else (value / target).toFloat().coerceIn(0f, 1f)
    val isMeasured: Boolean get() = target > 0.0
    val isReady: Boolean get() = isMeasured && value >= target
}

/** How progress compares with the season's clock. */
enum class ChamberPace(val label: String) { READY("Ready for the trial"), AHEAD("Ahead"), ON_TRACK("On track"), BEHIND("Behind") }

object ChamberCatalog {
    val pathStart: LocalDate = LocalDate.of(2026, 9, 7)

    val years: List<ChamberYear> = listOf(
        ChamberYear(1, "始", "The Beginning"),
        ChamberYear(2, "武", "The Warrior"),
        ChamberYear(3, "圣", "The Sage"),
    )

    val seasons: List<ChamberSeason> = listOf(
        Triple("根", "The Root", "autumn"),
        Triple("春", "The Spring", "winter"),
        Triple("腿", "The Kick", "spring"),
        Triple("一路", "The First Road", "summer"),
        Triple("桥", "The Bridge", "autumn"),
        Triple("飞", "First Flight", "winter"),
        Triple("剑", "The Sword", "spring"),
        Triple("远", "The Long Road", "summer"),
        Triple("易", "The Change", "autumn"),
        Triple("蝶", "The Butterfly", "winter"),
        Triple("游龙", "The Swimming Dragon", "spring"),
        Triple("传", "The Transmission", "summer"),
    ).mapIndexed { i, (hanzi, title, period) ->
        ChamberSeason(
            code = "S${i + 2}",
            number = i + 1,
            year = i / 4 + 1,
            hanzi = hanzi,
            title = title,
            period = period,
            start = pathStart.plusWeeks(13L * i),
        )
    }

    private val PLANK_GATE = "60-second front and side planks, and quiet two-foot landings first."
    private val MOBILITY_GATE = "A month of daily leg swings and Cossack squats first, and kicks only when warm."

    val chambers: List<Chamber> = listOf(
        // ── Year 1 · 始 The Beginning ──
        Chamber(1, "S2", "母拳", "The Mother Fist", "母", ChamberPillar.FORMS,
            "Xiao Hong Quan, all 58 moves, from memory, twice through, filmed.",
            learnFrom = "The move list in Lìliàng's form tracker.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "shaolin_xiao_hong_quan")),
        Chamber(2, "S2", "马步", "The Root", "马", ChamberPillar.BODY,
            "Five minutes in horse stance, thighs near parallel, filmed side-on.",
            metric = ChamberMetric(ChamberMetricKind.HOLD_SECONDS, target = 300.0, exercises = listOf("Horse Stance"))),
        Chamber(3, "S2", "六回合", "Six Rounds", "斗", ChamberPillar.STRIKING,
            "Six 3-minute rounds on the bag, 1 minute rest, combinations throughout, the sixth as sharp as the first.",
            learnFrom = "Lìliàng's boxing combos and the round timer.",
            metric = ChamberMetric(ChamberMetricKind.ROUNDS, target = 6.0, exercises = listOf("Heavy Bag", "Shadowboxing"), minSeconds = 170)),
        Chamber(4, "S3", "小念头", "The Little Idea", "念", ChamberPillar.FORMS,
            "Siu Nim Tau, slow and complete, from memory, filmed.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "wing_chun_siu_nim_tau")),
        Chamber(5, "S3", "鲤鱼打挺", "The Carp", "鲤", ChamberPillar.KICKS,
            "A kip-up from flat on your back, hands by the ears, three in a row, on a mat.",
            gate = PLANK_GATE),
        Chamber(6, "S3", "倒立", "The Handstand", "倒", ChamberPillar.BODY,
            "30 seconds chest-to-wall, five cartwheels each side, a forward roll to standing.",
            metric = ChamberMetric(ChamberMetricKind.HOLD_SECONDS, target = 30.0, exercises = listOf("Chest-to-Wall Handstand", "Handstand"))),
        Chamber(7, "S4", "八段锦", "The Eight Brocades", "锦", ChamberPillar.INNER,
            "Ba Duan Jin every morning for four weeks, then once through from memory, filmed.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "qigong_ba_duan_jin")),
        Chamber(8, "S4", "高腿", "Head-High", "腿", ChamberPillar.KICKS,
            "Round kick and front kick touching a head-height target, five clean each leg.",
            gate = MOBILITY_GATE),
        Chamber(9, "S4", "一字马", "The Line", "一", ChamberPillar.BODY,
            "Front split to the floor on your better side, within a hand's width on the other. Warm, never cold.",
            gate = MOBILITY_GATE),
        Chamber(10, "S5", "一路", "The First Road", "路", ChamberPillar.FORMS,
            "Chen Yi Lu, all 89 moves, from memory, filmed.",
            learnFrom = "One move a week, section by section, logged with the move wheel.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "chen_lao_jia_yi_lu_89")),
        Chamber(11, "S5", "转身后摆", "The Turning Heel", "转", ChamberPillar.KICKS,
            "The spinning heel kick to a head-height target, five clean each side."),
        Chamber(12, "S5", "引体", "The Pull", "引", ChamberPillar.BODY,
            "Five strict pull-ups.",
            metric = ChamberMetric(ChamberMetricKind.REPS, target = 5.0, exercises = listOf("Pull-Up", "Pull Up", "Pullups", "Pull-ups", "Strict Pull-Up"))),
        // ── Year 2 · 武 The Warrior ──
        Chamber(13, "S6", "寻桥", "Seeking the Bridge", "桥", ChamberPillar.FORMS,
            "Chum Kiu, from memory, filmed.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "wing_chun_chum_kiu")),
        Chamber(14, "S6", "三节棍", "The Three Sections", "节", ChamberPillar.WEAPONS,
            "A one-minute three-section staff sequence, spins, wraps and strikes, no self-hits on film.",
            learnFrom = "Basic three-section staff moves first, slowly, with a padded staff."),
        Chamber(15, "S6", "泰拳", "Muay Thai Rounds", "肘", ChamberPillar.STRIKING,
            "Five 3-minute Muay Thai rounds on the bag: teeps, knees, elbows, low and body kicks.",
            learnFrom = "Lìliàng's Muay Thai combos.",
            metric = ChamberMetric(ChamberMetricKind.ROUNDS, target = 5.0, exercises = listOf("Muay Thai", "Heavy Bag"), minSeconds = 170)),
        Chamber(16, "S7", "杨式", "The Yang Short Form", "杨", ChamberPillar.FORMS,
            "The Yang 37, from memory, filmed.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "yang_style_37")),
        Chamber(17, "S7", "二起脚", "The Double Rise", "起", ChamberPillar.KICKS,
            "The jumping front slap kick, slapping a head-height target, five clean.",
            gate = PLANK_GATE),
        Chamber(18, "S7", "旋风脚", "The Whirlwind", "风", ChamberPillar.KICKS,
            "The tornado kick slapping a chest-height target, landing on two feet, five clean, on a mat.",
            gate = PLANK_GATE),
        Chamber(19, "S8", "剑术", "The Straight Sword", "剑", ChamberPillar.WEAPONS,
            "A basic straight-sword sequence with a wooden sword, from memory, filmed."),
        Chamber(20, "S8", "腾空侧踹", "The Flying Side Kick", "飞", ChamberPillar.KICKS,
            "To a held shield or the bag, five clean, landing balanced.",
            gate = PLANK_GATE),
        Chamber(21, "S8", "五禽戏", "The Five Animals", "禽", ChamberPillar.INNER,
            "Wu Qin Xi, the Five Animal Frolics, from memory, filmed.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "qigong_wu_qin_xi")),
        Chamber(22, "S9", "太极八章", "The Eight Taegeuk", "章", ChamberPillar.FORMS,
            "Taegeuk 1 to 8 back to back, from memory, filmed."),
        Chamber(23, "S9", "枪术", "The Spear", "枪", ChamberPillar.WEAPONS,
            "A basic spear sequence: thrusts, circles and blocks, filmed."),
        Chamber(24, "S9", "十公里", "The Long Road", "远", ChamberPillar.BODY,
            "10 km continuous, any pace.",
            metric = ChamberMetric(ChamberMetricKind.DISTANCE_KM, target = 10.0)),
        // ── Year 3 · 圣 The Sage ──
        Chamber(25, "S10", "易筋经", "The Tendon Classic", "筋", ChamberPillar.INNER,
            "Yi Jin Jing, all twelve movements, from memory, filmed.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "qigong_yi_jin_jing")),
        Chamber(26, "S10", "刀术", "The Broadsword", "刀", ChamberPillar.WEAPONS,
            "A basic broadsword sequence with a wooden blade, filmed."),
        Chamber(27, "S10", "入门", "Through the Door", "门", ChamberPillar.STRIKING,
            "A term of ten or more classes at a club, ending in light technical sparring with protection."),
        Chamber(28, "S11", "高丽", "Koryo", "高", ChamberPillar.FORMS,
            "Koryo, the first black-belt poomsae, from memory, filmed."),
        Chamber(29, "S11", "旋子", "The Butterfly", "蝶", ChamberPillar.KICKS,
            "The butterfly kick, three clean on a mat.",
            gate = PLANK_GATE),
        Chamber(30, "S11", "六字诀", "The Six Sounds", "诀", ChamberPillar.INNER,
            "Liu Zi Jue from memory, breath and sound together, filmed.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "qigong_liu_zi_jue")),
        Chamber(31, "S12", "游龙", "The Swimming Dragon", "龙", ChamberPillar.WEAPONS,
            "The straight-sword sequence complete and flowing, from memory, filmed."),
        Chamber(32, "S12", "千本素振", "A Thousand Cuts", "千", ChamberPillar.WEAPONS,
            "1,000 bokken suburi in one session, sets of 100, form held to the last. Film the first ten and the last ten."),
        Chamber(33, "S12", "朝阳", "Facing the Sun", "阳", ChamberPillar.BODY,
            "Sun Salutation A and B, five rounds each, unbroken, at sunrise.",
            metric = ChamberMetric(ChamberMetricKind.FORM_MOVES, formId = "yoga_surya_namaskar_b")),
        Chamber(34, "S13", "演武", "The Demonstration", "演", ChamberPillar.FORMS,
            "The whole repertoire in one morning, filmed."),
        Chamber(35, "S13", "大考", "The Great Trial", "考", ChamberPillar.BODY,
            "In one week: head-high kicks both legs, the line, the kip-up, six rounds, ten pull-ups, 10 km, five minutes in horse stance."),
        Chamber(36, "S13", "第三十六房", "The 36th Chamber", "传", ChamberPillar.INNER,
            "Teach. At least four sessions of Ba Duan Jin or the Yang 37 for whoever will come."),
    )

    fun season(code: String): ChamberSeason? = seasons.firstOrNull { it.code == code }

    fun chambersIn(code: String): List<Chamber> = chambers.filter { it.seasonCode == code }

    fun chamber(number: Int): Chamber? = chambers.firstOrNull { it.number == number }

    /** The season in play on [today]: the first one before the path begins, the last one after it ends. */
    fun currentSeason(today: LocalDate): ChamberSeason =
        seasons.firstOrNull { it.contains(today) }
            ?: if (today.isBefore(pathStart)) seasons.first() else seasons.last()

    fun stateOf(chamber: Chamber, passed: Boolean, today: LocalDate): ChamberState {
        if (passed) return ChamberState.PASSED
        val season = season(chamber.seasonCode) ?: return ChamberState.LOCKED
        val current = currentSeason(today)
        return when {
            season.code == current.code -> if (today.isBefore(season.start)) ChamberState.UPCOMING else ChamberState.CURRENT
            today.isAfter(season.end) -> ChamberState.OPEN
            else -> ChamberState.LOCKED
        }
    }

    fun pace(progress: ChamberProgress, season: ChamberSeason, today: LocalDate): ChamberPace? {
        if (!progress.isMeasured) return null
        if (progress.isReady) return ChamberPace.READY
        if (today.isBefore(season.start)) return null
        val gap = progress.fraction - season.timeFraction(today)
        return when {
            gap > 0.1f -> ChamberPace.AHEAD
            gap < -0.15f -> ChamberPace.BEHIND
            else -> ChamberPace.ON_TRACK
        }
    }

    fun formatSeconds(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }
}

/** 1 → 一, 10 → 十, 14 → 十四, 20 → 二十, 36 → 三十六. Covers 1…99. */
fun chineseNumeral(n: Int): String {
    val digits = "〇一二三四五六七八九"
    require(n in 1..99) { "chineseNumeral covers 1…99" }
    val tens = n / 10
    val ones = n % 10
    return buildString {
        when {
            tens == 0 -> {}
            tens == 1 -> append("十")
            else -> { append(digits[tens]); append("十") }
        }
        if (ones != 0) append(digits[ones])
    }
}
