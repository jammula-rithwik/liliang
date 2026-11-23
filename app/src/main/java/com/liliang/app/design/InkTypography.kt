@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.liliang.app.design

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.liliang.app.R

/**
 * Two voices (Noto Serif SC was dropped with its ~400 KB in R6-6b2):
 *  - Fraunces, a soft wonky serif, for display numerals and editorial
 *    headings — the "big beautiful numbers" voice. High optical size for
 *    display cuts, low for small labels.
 *  - Ma Shan Zheng, genuine kaishu brush calligraphy, for ALL hanzi —
 *    stamps, watermarks, the dock, reading-level form names alike.
 * Body text stays on the system sans: it disappears, the serifs perform.
 */

private fun fraunces(weight: Int, opticalSize: Float) = Font(
    R.font.fraunces,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
        FontVariation.Setting("SOFT", 40f),
        FontVariation.Setting("WONK", 0f),
    ),
)

/** Display cut — masthead dates, hero numerals. */
val FrauncesDisplay = FontFamily(
    fraunces(weight = 300, opticalSize = 144f),
    fraunces(weight = 400, opticalSize = 144f),
    fraunces(weight = 500, opticalSize = 144f),
    fraunces(weight = 600, opticalSize = 144f),
)

/** Text cut — card titles, stat labels, smaller serif moments. */
val FrauncesText = FontFamily(
    fraunces(weight = 400, opticalSize = 28f),
    fraunces(weight = 500, opticalSize = 28f),
    fraunces(weight = 600, opticalSize = 28f),
)

/** Brush calligraphy — hero hanzi only. */
val Calligraphy = FontFamily(Font(R.font.ma_shan_zheng))

/** Tabular + lining figures so digits align in ledgers and don't jiggle when rolling. */
private const val TABULAR = "tnum, lnum"

object InkType {
    /** The session screen's working numerals (weight/reps). */
    val NumberHuge = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight(500),
        fontSize = 64.sp,
        lineHeight = 64.sp,
        fontFeatureSettings = TABULAR,
        letterSpacing = (-1).sp,
    )

    /** Dashboard hero stats. */
    val NumberLarge = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight(500),
        fontSize = 40.sp,
        lineHeight = 42.sp,
        fontFeatureSettings = TABULAR,
        letterSpacing = (-0.5).sp,
    )

    /** Inline stats, calendar day numbers, chart tags. */
    val NumberSmall = TextStyle(
        fontFamily = FrauncesText,
        fontWeight = FontWeight(500),
        fontSize = 18.sp,
        fontFeatureSettings = TABULAR,
    )

    /** Masthead — "Thursday 12 June". */
    val Masthead = TextStyle(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight(400),
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.25).sp,
    )

    /** Screen and section titles. */
    val Title = TextStyle(
        fontFamily = FrauncesText,
        fontWeight = FontWeight(600),
        fontSize = 22.sp,
        lineHeight = 26.sp,
    )

    /** Card titles, exercise names. */
    val Heading = TextStyle(
        fontFamily = FrauncesText,
        fontWeight = FontWeight(500),
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )

    /** Workhorse body. */
    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Secondary body / coach notes. */
    val BodyDim = Body.copy(fontSize = 13.sp, lineHeight = 18.sp)

    /**
     * Overlines, stamps' captions, axis labels.
     *
     * Voice rules (C2): section labels are plain UPPERCASE in BoneFaint, no
     * hanzi. Hanzi belongs to mastheads and subtitles ("库 · exercises &
     * combos", lowercase phrase) or to a room kicker ("史 · TRAINING RECORD",
     * accent colour) — never mixed into list section headers.
     */
    val Label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    )

    /** The smallest voice — chip captions, axis ticks, unit suffixes. */
    val LabelSmall = Label.copy(fontSize = 9.sp, lineHeight = 12.sp)

    /** One notch up from LabelSmall — the 10sp caption seven screens were
     *  hand-copying before it earned a token. */
    val Caption = Label.copy(fontSize = 10.sp, lineHeight = 13.sp)

    /** Compact body for dense rows — one notch above Body's caption uses. */
    val BodyStrong = Body.copy(fontSize = 15.sp)

    /**
     * Reading-level hanzi — form names, move lists. Brush calligraphy like
     * every other hanzi in the app, so the whole thing reads in one hand.
     */
    val Hanzi = TextStyle(
        fontFamily = Calligraphy,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    /** Brush hanzi in stamps and the dock. */
    val Seal = TextStyle(
        fontFamily = Calligraphy,
        fontSize = 22.sp,
        lineHeight = 24.sp,
    )

    /** Giant brush hanzi — art page headers, watermarks. */
    val SealHero = TextStyle(
        fontFamily = Calligraphy,
        fontSize = 96.sp,
        lineHeight = 100.sp,
    )
}

/**
 * Hanzi at an arbitrary size — discipline glyphs, form titles, hero glyphs.
 * Brush calligraphy (Ma Shan Zheng) so every Chinese character in the app is
 * the same hand. The handful of rare/traditional glyphs the brush face lacks
 * fall through to the system CJK font automatically.
 */
fun BrushHanzi(fontSize: androidx.compose.ui.unit.TextUnit) = TextStyle(
    fontFamily = Calligraphy,
    fontSize = fontSize,
    lineHeight = fontSize * 1.08f,
)

/** Bridge so stock M3 widgets inherit the voices. */
@Composable
fun liliangMaterialTypography(): Typography {
    return Typography(
        displayLarge = InkType.NumberLarge,
        displayMedium = InkType.Masthead,
        headlineLarge = InkType.Title.copy(fontSize = 26.sp),
        headlineMedium = InkType.Title,
        headlineSmall = InkType.Heading.copy(fontSize = 19.sp),
        titleLarge = InkType.Title,
        titleMedium = InkType.Heading,
        titleSmall = InkType.Heading.copy(fontSize = 15.sp),
        bodyLarge = InkType.BodyStrong,
        bodyMedium = InkType.Body,
        bodySmall = InkType.BodyDim,
        labelLarge = InkType.Label,
        labelMedium = InkType.Label,
        labelSmall = InkType.Label,
    )
}
