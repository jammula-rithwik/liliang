package com.liliang.app.data

// Martial-arts form tracking: the SEED catalogue of named forms (each a
// fixed, ordered list of movements) plus the progress-tracking models the
// repository layer persists per move.
//
// Runtime truth is the DATABASE (martial_arts_forms + _moves, user-editable
// and ingest-fed); this file only seeds it on fresh installs/migrations. UI
// code reads the hall snapshot, never these lists. The first seeded form is
// the 89-movement Chen-style routine.

/**
 * One named movement inside a martial-arts form. The [name] string is the
 * traditional Chinese characters for the move and is rendered as-is — there is
 * no transliteration or translation, deliberately, because the user is
 * learning the form using the Chinese names directly.
 *
 * [section] groups movements into the form's traditional sections (e.g.
 * "First Section", "Second Section") so the linear list can be broken up
 * visually without making the user read 89 names back-to-back.
 */
data class MartialArtsMove(
    val index: Int,
    val name: String,
    val section: String = "",
)

/**
 * A complete martial-arts form. [id] is a stable string used as the storage
 * key in the progress table — never change this once a form has been used by
 * a real user, or their progress will be orphaned.
 */
data class MartialArtsForm(
    val id: String,
    val title: String,
    val subtitle: String = "",
    /** Discipline this form belongs to ("Tai Chi"), used to group forms per discipline card. */
    val discipline: String = "Tai Chi",
    /** Compact Latin-alphabet label for chips and tiles where the full Chinese title won't fit. */
    val shortTitle: String = "",
    /**
     * Set when the user marks the whole form as mastered. Mastered forms stop
     * showing checklists and percentages — they're part of the lineage, not a
     * project. Forms with zero moves are tracked at this whole-form level only.
     */
    val masteredAt: String = "",
    val moves: List<MartialArtsMove> = emptyList(),
) {
    val moveCount: Int get() = moves.size
    val displayShortTitle: String get() = shortTitle.ifBlank { title }
    val isMastered: Boolean get() = masteredAt.isNotBlank()
}

/**
 * Per-move progress record. [learnedAt] is an ISO timestamp string (or blank
 * for "not yet learned"); the repository writes/reads through this shape.
 */
data class MartialArtsMoveProgress(
    val moveIndex: Int,
    val learnedAt: String = "",
    val notes: String = "",
) {
    val isLearned: Boolean get() = learnedAt.isNotBlank()
}

/**
 * Snapshot of a user's progress through a specific form. Built by the
 * repository (one query for the form's progress rows) and consumed by the
 * dashboard tile + the Martial Arts browse view.
 */
data class MartialArtsFormProgress(
    val formId: String,
    val totalMoves: Int,
    val progressByIndex: Map<Int, MartialArtsMoveProgress> = emptyMap(),
) {
    val learnedCount: Int get() = progressByIndex.values.count { it.isLearned }
    val percent: Float get() = if (totalMoves == 0) 0f else learnedCount.toFloat() / totalMoves

    /** Most-recently learned move (highest learnedAt timestamp), or null if none. */
    val mostRecentlyLearned: MartialArtsMoveProgress?
        get() = progressByIndex.values.filter { it.isLearned }.maxByOrNull { it.learnedAt }

    /**
     * The "currently learning" move — the first move in form-order that hasn't
     * been marked learned yet. This is the move the user is most likely working
     * on right now, which the dashboard tile and form view both highlight.
     */
    fun currentlyLearningIndex(form: MartialArtsForm): Int? {
        val firstUnlearned = form.moves.firstOrNull { !(progressByIndex[it.index]?.isLearned ?: false) }
        return firstUnlearned?.index
    }

    fun isLearned(moveIndex: Int): Boolean = progressByIndex[moveIndex]?.isLearned ?: false
    fun learnedAt(moveIndex: Int): String = progressByIndex[moveIndex]?.learnedAt.orEmpty()
}

// ───────────────────────── Discipline catalogue & hall stats ─────────────────────────

/**
 * The martial-arts disciplines the user trains or has trained. Static
 * catalogue (like forms): each gets a card in the Martial Arts hall whether
 * or not it has logged hours yet — past arts are part of the lineage, and an
 * empty card is an invitation.
 */
data class MartialArtsDiscipline(
    val name: String,
    /** Hanzi shown large on the discipline card. */
    val glyph: String,
    /** One-line flavour text under the name. */
    val tagline: String,
)

/**
 * Seed catalogue of disciplines. Like forms, the runtime list lives in the
 * database (`martial_arts_disciplines`) so new arts — Kendo, whatever comes
 * next — can be added in-app; this seeds it.
 */
object MartialArtsDisciplines {
    val entries: List<MartialArtsDiscipline> = listOf(
        MartialArtsDiscipline("Tai Chi", "太极", "Chen & Yang style — softness that overcomes hardness"),
        MartialArtsDiscipline("Muay Thai", "泰拳", "The art of eight limbs"),
        MartialArtsDiscipline("Boxing", "拳击", "The sweet science"),
        MartialArtsDiscipline("Taekwondo", "跆拳道", "The way of foot and fist"),
        MartialArtsDiscipline("Wing Chun", "咏春", "Centreline, economy, intent"),
        MartialArtsDiscipline("Shaolin Kung Fu", "少林", "Where it all began"),
        MartialArtsDiscipline("Qigong", "气功", "Breath, posture, intention"),
        MartialArtsDiscipline("Yoga", "瑜伽", "Strength in stillness"),
    )
}

/** Aggregated training stats for one discipline, computed from history. */
data class DisciplineStats(
    val name: String = "",
    val totalHours: Double = 0.0,
    val thisYearHours: Double = 0.0,
    val thisYearSessions: Int = 0,
    val lastTrainedDate: String = "",
    val comboCount: Int = 0,
)

/** One logged martial-arts session (a date × exercise rollup) for the hall timeline. */
data class MartialArtsSessionRecord(
    val date: String = "",
    val title: String = "",
    val discipline: String = "",
    val totalSeconds: Int = 0,
    val rounds: Int = 0,
)

/** Everything the Martial Arts hall screen renders besides form progress. */
data class MartialArtsHallSnapshot(
    val forms: List<MartialArtsForm> = emptyList(),
    val disciplines: List<MartialArtsDiscipline> = emptyList(),
    val disciplineStats: List<DisciplineStats> = emptyList(),
    val recentSessions: List<MartialArtsSessionRecord> = emptyList(),
    val checkpointsByForm: Map<String, List<FormCheckpoint>> = emptyMap(),
)

// ───────────────────────── Combo moves ─────────────────────────
//
// Static palette of standard boxing / martial-arts moves the combo builder
// offers. Each move has:
//   - [id]: stable string saved to disk inside the combo's `moves_json` column.
//     Don't change it once a combo references it, or moves saved earlier will
//     stop deserialising.
//   - [displayName]: chip label in the builder UI.
//   - [callout]: phrase the TTS engine speaks during the round timer.
//   - [category]: visual grouping in the builder palette.
//
// Adding a new move is appending an entry to [ComboMove]. Custom user-defined
// moves are out of scope; if a combo wants something unusual, the user can put
// it in the combo's notes field today.

enum class ComboMoveCategory(val label: String) {
    PUNCH("Punches"),
    DEFENSE("Defense"),
    MOVEMENT("Movement"),
    KICK("Kicks"),
}

enum class ComboMove(
    val id: String,
    val displayName: String,
    val callout: String,
    val category: ComboMoveCategory,
) {
    JAB("jab", "Jab", "Jab", ComboMoveCategory.PUNCH),
    CROSS("cross", "Cross", "Cross", ComboMoveCategory.PUNCH),
    LEFT_HOOK("left_hook", "Left hook", "Left hook", ComboMoveCategory.PUNCH),
    RIGHT_HOOK("right_hook", "Right hook", "Right hook", ComboMoveCategory.PUNCH),
    LEFT_UPPERCUT("left_uppercut", "Left uppercut", "Left uppercut", ComboMoveCategory.PUNCH),
    RIGHT_UPPERCUT("right_uppercut", "Right uppercut", "Right uppercut", ComboMoveCategory.PUNCH),
    OVERHAND("overhand", "Overhand", "Overhand", ComboMoveCategory.PUNCH),
    SLIP_LEFT("slip_left", "Slip left", "Slip left", ComboMoveCategory.DEFENSE),
    SLIP_RIGHT("slip_right", "Slip right", "Slip right", ComboMoveCategory.DEFENSE),
    ROLL("roll", "Roll", "Roll", ComboMoveCategory.DEFENSE),
    DUCK("duck", "Duck", "Duck", ComboMoveCategory.DEFENSE),
    PIVOT("pivot", "Pivot", "Pivot", ComboMoveCategory.MOVEMENT),
    STEP_BACK("step_back", "Step back", "Step back", ComboMoveCategory.MOVEMENT),
    STEP_LEFT("step_left", "Step left", "Step left", ComboMoveCategory.MOVEMENT),
    STEP_RIGHT("step_right", "Step right", "Step right", ComboMoveCategory.MOVEMENT),
    FRONT_KICK("front_kick", "Front kick", "Front kick", ComboMoveCategory.KICK),
    ROUND_KICK("round_kick", "Round kick", "Round kick", ComboMoveCategory.KICK),
    SIDE_KICK("side_kick", "Side kick", "Side kick", ComboMoveCategory.KICK),
    LEFT_KNEE("left_knee", "Left knee", "Left knee", ComboMoveCategory.KICK),
    RIGHT_KNEE("right_knee", "Right knee", "Right knee", ComboMoveCategory.KICK),
    TEEP("teep", "Teep", "Teep", ComboMoveCategory.KICK);

    companion object {
        fun fromId(id: String): ComboMove? = entries.firstOrNull { it.id == id }

        /**
         * Group the full move catalogue by [ComboMoveCategory] in declaration
         * order — used by the combo builder palette to render one section per
         * category.
         */
        fun groupedByCategory(): Map<ComboMoveCategory, List<ComboMove>> =
            entries.groupBy { it.category }
    }
}

/**
 * Seed catalogue of forms. The runtime source of truth is the database
 * (`martial_arts_forms` + `martial_arts_form_moves`, user-editable, so new
 * forms can be added in-app) — this object exists to seed it on migration and
 * fresh installs. After seeding, edits live in the DB only.
 *
 * Forms with full move lists get the per-move learning tracker; forms seeded
 * without moves (the Taekwondo poomsae, Xiao Hong Quan) are tracked at the
 * whole-form level — mark mastered, or add the moves later in the editor.
 */
object MartialArtsForms {

    /**
     * Chen-style Tai Chi Old Frame 1st Routine (陈氏太极拳老架一路, "Yi Lu") —
     * 89-movement counting. Section breaks follow the traditional 4-section
     * division used in modern published transcripts of this routine.
     */
    val ChenLaoJiaYiLu89: MartialArtsForm = MartialArtsForm(
        // The id keeps its historical name — progress and checkpoints key on
        // it — but the displayed name is just Yi Lu, as the user calls it.
        id = "chen_lao_jia_yi_lu_89",
        title = "陈氏太极拳一路",
        subtitle = "89式 · Chen Style Yi Lu",
        discipline = "Tai Chi",
        shortTitle = "Yi Lu",
        moves = chenLaoJia89Moves(),
    )

    /**
     * Yang-style 37-posture short form (杨氏太极拳三十七式, the Cheng Man-ching
     * counting). Section breaks follow the common three-part division.
     */
    val YangStyle37: MartialArtsForm = MartialArtsForm(
        id = "yang_style_37",
        title = "杨氏太极拳三十七式",
        subtitle = "37式 · Yang Style Short Form",
        discipline = "Tai Chi",
        shortTitle = "Yang 37",
        moves = yangStyle37Moves(),
    )

    /** 小念头 — Wing Chun's first form. Movement names per the common Ip Man-line syllabus. */
    val SiuNimTau: MartialArtsForm = MartialArtsForm(
        id = "wing_chun_siu_nim_tau",
        title = "小念头",
        subtitle = "Siu Nim Tau · Wing Chun first form",
        discipline = "Wing Chun",
        shortTitle = "Siu Nim Tau",
        moves = siuNimTauMoves(),
    )

    /** 寻桥 — Wing Chun's second form ("Seeking the Bridge"). */
    val ChumKiu: MartialArtsForm = MartialArtsForm(
        id = "wing_chun_chum_kiu",
        title = "寻桥",
        subtitle = "Chum Kiu · Wing Chun second form",
        discipline = "Wing Chun",
        shortTitle = "Chum Kiu",
        moves = chumKiuMoves(),
    )

    /**
     * 小洪拳 — Shaolin's foundational routine, "the mother of Shaolin fist
     * forms". Sequence follows the traditional 拳谱 (16th road) enumeration;
     * lineages vary, so edit to match your school if needed.
     */
    val XiaoHongQuan: MartialArtsForm = MartialArtsForm(
        id = "shaolin_xiao_hong_quan",
        title = "小洪拳",
        subtitle = "Xiao Hong Quan · 58式 · traditional 拳谱 sequence",
        discipline = "Shaolin Kung Fu",
        shortTitle = "Xiao Hong Quan",
        moves = xiaoHongQuanMoves(),
    )

    /** 八段锦 — the Eight Brocades, Health Qigong standard sequence. */
    val BaDuanJin: MartialArtsForm = MartialArtsForm(
        id = "qigong_ba_duan_jin",
        title = "八段锦",
        subtitle = "Ba Duan Jin · Eight Brocades",
        discipline = "Qigong",
        shortTitle = "Ba Duan Jin",
        moves = baDuanJinMoves(),
    )

    /** 易筋经 — Muscle/Tendon Change Classic, Health Qigong 12-movement standard. */
    val YiJinJing: MartialArtsForm = MartialArtsForm(
        id = "qigong_yi_jin_jing",
        title = "易筋经",
        subtitle = "Yi Jin Jing · Muscle-Tendon Change Classic",
        discipline = "Qigong",
        shortTitle = "Yi Jin Jing",
        moves = yiJinJingMoves(),
    )

    /** 五禽戏 — Five Animal Frolics, Health Qigong standard. */
    val WuQinXi: MartialArtsForm = MartialArtsForm(
        id = "qigong_wu_qin_xi",
        title = "五禽戏",
        subtitle = "Wu Qin Xi · Five Animal Frolics",
        discipline = "Qigong",
        shortTitle = "Wu Qin Xi",
        moves = wuQinXiMoves(),
    )

    /** 六字诀 — Six Healing Sounds, Health Qigong standard. */
    val LiuZiJue: MartialArtsForm = MartialArtsForm(
        id = "qigong_liu_zi_jue",
        title = "六字诀",
        subtitle = "Liu Zi Jue · Six Healing Sounds",
        discipline = "Qigong",
        shortTitle = "Liu Zi Jue",
        moves = liuZiJueMoves(),
    )

    /** Surya Namaskar A — Ashtanga sun salutation A. */
    val SunSalutationA: MartialArtsForm = MartialArtsForm(
        id = "yoga_surya_namaskar_a",
        title = "Surya Namaskar A",
        subtitle = "Sun Salutation A · Ashtanga",
        discipline = "Yoga",
        shortTitle = "Sun Salutation A",
        moves = sunSalutationAMoves(),
    )

    /** Surya Namaskar B — Ashtanga sun salutation B. */
    val SunSalutationB: MartialArtsForm = MartialArtsForm(
        id = "yoga_surya_namaskar_b",
        title = "Surya Namaskar B",
        subtitle = "Sun Salutation B · Ashtanga",
        discipline = "Yoga",
        shortTitle = "Sun Salutation B",
        moves = sunSalutationBMoves(),
    )

    /**
     * World Taekwondo poomsae through 1st dan: Taegeuk 1–8 plus Koryo.
     * Tracked whole-form (mark mastered as you grade) — per-technique lists
     * can be added in the editor if wanted.
     */
    val TaekwondoPoomsae: List<MartialArtsForm> = listOf(
        "Il Jang" to 18, "Yi Jang" to 18, "Sam Jang" to 20, "Sa Jang" to 20,
        "O Jang" to 20, "Yuk Jang" to 23, "Chil Jang" to 25, "Pal Jang" to 24,
    ).mapIndexed { index, (name, count) ->
        MartialArtsForm(
            id = "tkd_taegeuk_${index + 1}",
            title = "太极${index + 1}章",
            subtitle = "Taegeuk $name · $count movements",
            discipline = "Taekwondo",
            shortTitle = "Taegeuk ${index + 1}",
        )
    } + MartialArtsForm(
        id = "tkd_koryo",
        title = "高丽",
        subtitle = "Koryo · 1st dan poomsae · 30 movements",
        discipline = "Taekwondo",
        shortTitle = "Koryo",
    )

    /** Seed entries, in display order. */
    val entries: List<MartialArtsForm> =
        listOf(ChenLaoJiaYiLu89, YangStyle37, SiuNimTau, ChumKiu, XiaoHongQuan) + TaekwondoPoomsae +
            listOf(BaDuanJin, YiJinJing, WuQinXi, LiuZiJue, SunSalutationA, SunSalutationB)

    fun byId(id: String): MartialArtsForm? = entries.firstOrNull { it.id == id }
}

/**
 * 小念头 main movements in the widely-taught Ip Man-line order — the opening,
 * the slow saam pai fut section, and the remaining two sections. Cantonese
 * names with characters, since that's how Wing Chun is usually taught.
 */
private fun siuNimTauMoves(): List<MartialArtsMove> {
    val first = listOf(
        "开马 Hoi Ma (opening stance)",
        "交叉耕手 Gaau Cha Gang Sau",
        "交叉摊手 Gaau Cha Tan Sau",
        "收拳 Sau Kuen",
        "日字冲拳 Yat Ji Chung Kuen (left)",
        "日字冲拳 Yat Ji Chung Kuen (right)",
        "摊手 Tan Sau",
        "圈手 Huen Sau",
        "护手 Wu Sau",
        "伏手 Fook Sau (×3 cycle)",
        "横掌 Wang Jeung (side palm)",
        "正掌 Jing Jeung (vertical palm)",
    )
    val second = listOf(
        "左右按手 Gum Sau (left & right)",
        "后按手 Hau Gum Sau",
        "前按手 Chin Gum Sau",
        "拦手 Lan Sau",
        "挥手 Fak Sau",
        "标指手 Biu Sau",
        "长桥按掌 Cheung Kiu Gum Sau",
        "双提手 Tai Sau",
    )
    val third = listOf(
        "脱手 Tut Sau",
        "膀手 Bong Sau",
        "脱颈手 Tut Geng Sau",
        "底掌 Dai Jeung",
        "连环冲拳 Lin Wan Chung Kuen (chain punches)",
        "收式 Sau Sik (closing)",
    )
    return buildList {
        first.forEachIndexed { i, name -> add(MartialArtsMove(index = i + 1, name = name, section = "一段")) }
        second.forEachIndexed { i, name -> add(MartialArtsMove(index = first.size + i + 1, name = name, section = "二段")) }
        third.forEachIndexed { i, name -> add(MartialArtsMove(index = first.size + second.size + i + 1, name = name, section = "三段")) }
    }
}

/** 寻桥 main movements — the turning, bridging and kicking sections. */
private fun chumKiuMoves(): List<MartialArtsMove> {
    val names = listOf(
        "开马 Hoi Ma (opening)",
        "交叉耕手 / 摊手 opening cycle",
        "日字冲拳 Yat Ji Chung Kuen",
        "拦手转马 Lan Sau with turning stance",
        "双膀手 Sheung Bong Sau",
        "拂手冲拳 Fut Sau into punch",
        "转马拦手 Juen Ma Lan Sau (×3)",
        "侧身膀手 Side-body Bong Sau",
        "直踢 Jik Tek (front kick)",
        "斜踢 Waang Tek (side kick)",
        "低膀手 Dai Bong Sau",
        "双摊手 Sheung Tan Sau",
        "标指上掌 Biu Ji into rising palm",
        "拍手底掌 Pak Sau Dai Jeung",
        "连环冲拳 Lin Wan Chung Kuen",
        "收式 Sau Sik (closing)",
    )
    fun sectionFor(index: Int): String = when {
        index <= 6 -> "一段"
        index <= 11 -> "二段"
        else -> "三段"
    }
    return names.mapIndexed { i, name ->
        MartialArtsMove(index = i + 1, name = name, section = sectionFor(i + 1))
    }
}

/**
 * The 37 postures of the Yang-style short form in the Cheng Man-ching
 * counting, with the common three-section division. Names stay in characters,
 * matching the user's preference across the martial-arts catalogue.
 */
private fun yangStyle37Moves(): List<MartialArtsMove> {
    val names = listOf(
        "预备式",          // 1
        "起势",            // 2
        "揽雀尾",          // 3
        "单鞭",            // 4
        "提手上势",        // 5
        "靠",              // 6
        "白鹤亮翅",        // 7
        "搂膝拗步",        // 8
        "手挥琵琶",        // 9
        "进步搬拦捶",      // 10
        "如封似闭",        // 11
        "十字手",          // 12
        "抱虎归山",        // 13
        "肘底看捶",        // 14
        "倒撵猴",          // 15
        "斜飞式",          // 16
        "云手",            // 17
        "单鞭下势",        // 18
        "金鸡独立",        // 19
        "右分脚",          // 20
        "左分脚",          // 21
        "转身蹬脚",        // 22
        "进步栽捶",        // 23
        "上步揽雀尾",      // 24
        "单鞭",            // 25
        "玉女穿梭",        // 26
        "揽雀尾",          // 27
        "单鞭",            // 28
        "单鞭下势",        // 29
        "上步七星",        // 30
        "退步跨虎",        // 31
        "转身摆莲",        // 32
        "弯弓射虎",        // 33
        "进步搬拦捶",      // 34
        "如封似闭",        // 35
        "十字手",          // 36
        "合太极",          // 37
    )
    fun sectionFor(index: Int): String = when {
        index <= 12 -> "一段"
        index <= 22 -> "二段"
        else -> "三段"
    }
    return names.mapIndexed { i, name ->
        MartialArtsMove(index = i + 1, name = name, section = sectionFor(i + 1))
    }
}

/**
 * The 89 movements in Chen-style Tai Chi Old Frame 1st Routine, with section
 * grouping. Section names are descriptive (一段 / 二段 / 三段 / 四段) rather than
 * translated, matching the user's preference to keep everything in characters.
 *
 * Section boundaries follow common transcripts of this routine:
 *  - 一段: 1–17 (opening through 第三金刚捣碓)
 *  - 二段: 18–31 (庇身锤 through 单鞭)
 *  - 三段: 32–55 (云手 through 单鞭)
 *  - 四段: 56–89 (双震脚 through 收式)
 */
private fun chenLaoJia89Moves(): List<MartialArtsMove> {
    val names = listOf(
        "预备式",                                  // 1
        "起式",                                   // 2
        "金刚捣碓",                                // 3
        "懒扎衣",                                 // 4
        "六封四闭",                                // 5
        "单鞭",                                   // 6
        "第二金刚捣碓",                             // 7
        "白鹤亮翅",                                // 8
        "斜行",                                   // 9
        "初收",                                   // 10
        "前趟拗步",                                // 11
        "第二斜行",                                // 12
        "再收",                                   // 13
        "第二前趟拗步",                             // 14
        "掩手肱捶",                                // 15
        "十字手",                                 // 16
        "第三金刚捣碓",                             // 17
        "庇身锤（含背折靠）",                        // 18
        "青龙出水",                                // 19
        "双推掌",                                 // 20
        "三换掌",                                 // 21
        "肘底锤",                                 // 22
        "倒卷肱",                                 // 23
        "退步压肘",                                // 24
        "中盘",                                   // 25
        "白鹤亮翅",                                // 26
        "斜行拗步",                                // 27
        "闪通背",                                 // 28
        "掩手肱捶",                                // 29
        "大六封四闭",                              // 30
        "单鞭",                                   // 31
        "云手",                                   // 32
        "高探马",                                 // 33
        "右擦脚",                                 // 34
        "左擦脚",                                 // 35
        "转身左蹬脚",                              // 36
        "前趟拗步",                                // 37
        "击地捶",                                 // 38
        "翻身二起脚（含神仙一把抓）",                  // 39
        "护心捶",                                 // 40
        "旋风脚",                                 // 41
        "右蹬一根",                                // 42
        "海底翻花",                                // 43
        "掩手肱捶",                                // 44
        "小擒打",                                 // 45
        "抱头推山（含金丝缠腕）",                     // 46
        "三换掌",                                 // 47
        "六封四闭",                                // 48
        "单鞭",                                   // 49
        "前招",                                   // 50
        "后招",                                   // 51
        "右野马分鬃",                              // 52
        "左野马分鬃",                              // 53
        "大六封四闭",                              // 54
        "单鞭",                                   // 55
        "双震脚",                                 // 56
        "玉女穿梭",                                // 57
        "懒扎衣",                                 // 58
        "六封四闭",                                // 59
        "单鞭",                                   // 60
        "云手",                                   // 61
        "双摆莲",                                 // 62
        "跌叉",                                   // 63
        "右金鸡独立",                              // 64
        "左金鸡独立",                              // 65
        "倒卷肱",                                 // 66
        "退步压肘",                                // 67
        "中盘",                                   // 68
        "白鹤亮翅",                                // 69
        "斜行拗步",                                // 70
        "闪通背",                                 // 71
        "掩手肱捶",                                // 72
        "大六封四闭",                              // 73
        "单鞭",                                   // 74
        "云手",                                   // 75
        "高探马",                                 // 76
        "十字单摆莲",                              // 77
        "海底翻花",                                // 78
        "指裆捶",                                 // 79
        "白猿献果",                                // 80
        "小六封四闭",                              // 81
        "单鞭",                                   // 82
        "雀地龙",                                 // 83
        "上步骑麟（上步七星）",                       // 84
        "下步跨虎",                                // 85
        "转身双摆莲",                              // 86
        "当头炮",                                 // 87
        "金刚捣碓",                                // 88
        "收式",                                   // 89
    )
    fun sectionFor(index: Int): String = when {
        index <= 17 -> "一段"
        index <= 31 -> "二段"
        index <= 55 -> "三段"
        else -> "四段"
    }
    return names.mapIndexed { i, name ->
        MartialArtsMove(index = i + 1, name = name, section = sectionFor(i + 1))
    }
}

/**
 * 小洪拳 in the traditional Shaolin 拳谱 enumeration (第十六路), 58 movements.
 * Three-section split is editorial (the source runs continuously).
 */
private fun xiaoHongQuanMoves(): List<MartialArtsMove> {
    val names = listOf(
        "预备式", "操手", "怀中抱月", "白云盖顶", "上步推掌", "抱手束身", "上步一掌", "转脸扳手",
        "上步一掌", "外摆莲", "斜形束身劈腿", "斜形马步", "仆步单叉掠手", "老虎大张嘴", "云顶七星",
        "七星拳", "马步单鞭", "抱手束身", "跟步双抢手", "原地旋风脚",
        "振脚冲天炮", "蝎子尾", "猛虎夺食", "原地推掌", "平掌勾抓倒两步", "转身扳手", "上步推掌",
        "转身捧腿", "采脚左盘肘", "压手束身", "开半步劈腿", "右盘肘", "仆步单叉", "老虎大张嘴",
        "云顶七星", "马步单鞭", "抱手束身", "跟步双抢手", "单踩脚右一掌", "提右脚金鸡独立",
        "右推掌", "左采脚左一掌", "提左膝金鸡独立", "左推掌", "劈心掌", "回身掌", "跳步海底捞月",
        "海底炮", "上半步转身出阳拳", "撤步提膝摘耳炮", "落步下阴拳", "滚手束身", "起身推一掌",
        "半拉云顶击手炮", "左劈山", "右劈山", "五花坐山", "收式",
    )
    fun sectionFor(index: Int): String = when {
        index <= 20 -> "一段"
        index <= 40 -> "二段"
        else -> "三段"
    }
    return names.mapIndexed { i, name ->
        MartialArtsMove(index = i + 1, name = name, section = sectionFor(i + 1))
    }
}

/** 八段锦 — Health Qigong standard: preparation, the eight brocades, closing. */
private fun baDuanJinMoves(): List<MartialArtsMove> = listOf(
    "预备势",
    "两手托天理三焦",
    "左右开弓似射雕",
    "调理脾胃须单举",
    "五劳七伤往后瞧",
    "摇头摆尾去心火",
    "两手攀足固肾腰",
    "攒拳怒目增气力",
    "背后七颠百病消",
    "收势",
).mapIndexed { i, name -> MartialArtsMove(index = i + 1, name = name) }

/** 易筋经 — Health Qigong 12-movement standard with preparation and closing. */
private fun yiJinJingMoves(): List<MartialArtsMove> = listOf(
    "预备势",
    "韦驮献杵第一势",
    "韦驮献杵第二势",
    "韦驮献杵第三势",
    "摘星换斗势",
    "倒拽九牛尾势",
    "出爪亮翅势",
    "九鬼拔马刀势",
    "三盘落地势",
    "青龙探爪势",
    "卧虎扑食势",
    "打躬势",
    "掉尾势",
    "收势",
).mapIndexed { i, name -> MartialArtsMove(index = i + 1, name = name) }

/** 五禽戏 — Health Qigong standard: two movements per animal. */
private fun wuQinXiMoves(): List<MartialArtsMove> = listOf(
    "起势调息",
    "虎举", "虎扑",
    "鹿抵", "鹿奔",
    "熊运", "熊晃",
    "猿提", "猿摘",
    "鸟伸", "鸟飞",
    "引气归元",
).mapIndexed { i, name -> MartialArtsMove(index = i + 1, name = name) }

/** 六字诀 — Health Qigong standard: the six sounds with opening and closing. */
private fun liuZiJueMoves(): List<MartialArtsMove> = listOf(
    "起势",
    "嘘字诀",
    "呵字诀",
    "呼字诀",
    "呬字诀",
    "吹字诀",
    "嘻字诀",
    "收势",
).mapIndexed { i, name -> MartialArtsMove(index = i + 1, name = name) }

/** Surya Namaskar A — Ashtanga count. */
private fun sunSalutationAMoves(): List<MartialArtsMove> = listOf(
    "Samasthiti — Mountain",
    "Urdhva Hastasana — arms overhead",
    "Uttanasana — forward fold",
    "Ardha Uttanasana — half lift",
    "Chaturanga Dandasana — low plank",
    "Urdhva Mukha Svanasana — upward dog",
    "Adho Mukha Svanasana — downward dog (5 breaths)",
    "Ardha Uttanasana — half lift",
    "Uttanasana — forward fold",
    "Urdhva Hastasana — arms overhead",
    "Samasthiti — Mountain",
).mapIndexed { i, name -> MartialArtsMove(index = i + 1, name = name) }

/** Surya Namaskar B — Ashtanga count. */
private fun sunSalutationBMoves(): List<MartialArtsMove> = listOf(
    "Utkatasana — chair",
    "Uttanasana — forward fold",
    "Ardha Uttanasana — half lift",
    "Chaturanga Dandasana — low plank",
    "Urdhva Mukha Svanasana — upward dog",
    "Adho Mukha Svanasana — downward dog",
    "Virabhadrasana I — warrior one (right)",
    "Chaturanga Dandasana — low plank",
    "Urdhva Mukha Svanasana — upward dog",
    "Adho Mukha Svanasana — downward dog",
    "Virabhadrasana I — warrior one (left)",
    "Chaturanga Dandasana — low plank",
    "Urdhva Mukha Svanasana — upward dog",
    "Adho Mukha Svanasana — downward dog (5 breaths)",
    "Ardha Uttanasana — half lift",
    "Uttanasana — forward fold",
    "Utkatasana — chair",
    "Samasthiti — Mountain",
).mapIndexed { i, name -> MartialArtsMove(index = i + 1, name = name) }
