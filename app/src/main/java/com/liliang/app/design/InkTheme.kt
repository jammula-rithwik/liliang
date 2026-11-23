package com.liliang.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Lìliàng's palette — ink on washi-black paper. One theme, always dark:
 * the app is OLED-black by design, not by system preference.
 */
object InkColors {
    /** The page. True near-black with a warm undertone, never pure #000. */
    val Paper = Color(0xFF0B0A08)

    /** Raised surfaces — cards, sheets. */
    val PaperRaised = Color(0xFF15120E)

    /** Highest surfaces — dialogs, pressed states, pinned bars. */
    val PaperHigh = Color(0xFF1E1A14)

    /** Primary text — aged bone, not white. */
    val Bone = Color(0xFFE9E1D2)

    /** Secondary text. */
    val BoneDim = Color(0xFFAFA694)

    /** Tertiary text, disabled, watermark strokes. */
    val BoneFaint = Color(0xFF6E6657)

    /** Hairlines and card borders. */
    val Line = Color(0xFF2B261F)

    /** The seal. Buttons, active states, today, the dock thumb. */
    val Vermillion = Color(0xFFC73E2E)

    /** Pressed vermillion / deep accents. */
    val VermillionDeep = Color(0xFF992D20)

    /** Gold leaf — PRs, mastery, achievement. Used sparingly. */
    val Gold = Color(0xFFC9A227)

    /** Shimmer highlight on gold. */
    val GoldBright = Color(0xFFF5D879)

    /** Diluted ink — chart washes, quiet fills. */
    val InkWash = Color(0xFF41525B)

    /** Success/positive that isn't gold-worthy. */
    val Moss = Color(0xFF6F8F6A)

    // ── Carved wood — beams, plaques, tags (the hanging-menu language) ──
    /** Hard shadow line around carved wood. */
    val WoodEdge = Color(0xFF0E0A07)

    // ── Category accents, re-inked ─────────────────────────────────────
    /** Lifts — cold steel. */
    val Iron = Color(0xFF9AA7B0)

    /** Running — jade. */
    val Jade = Color(0xFF7FA98E)

    /** Martial arts — the seal itself. */
    val Art = Vermillion

    /** Qigong / yoga / breath — morning mist. */
    val Mist = Color(0xFF8FA3B8)

    /** 石青 azurite, the painter's mineral blue — superset B against A's vermillion. */
    val Azurite = Color(0xFF4A7FBF)

    /**
     * Superset letters take these in order: A red, B blue, as Greg asked on
     * 23 Sep 2026, then gold, jade, mist. Same letter, same colour, same group.
     */
    private val SupersetPalette = listOf(Vermillion, Azurite, Gold, Jade, Mist)

    fun superset(index: Int): Color = SupersetPalette[index.mod(SupersetPalette.size)]

    /** Map a workout category string to its accent. */
    fun forCategory(category: String?): Color = when (category?.trim()?.lowercase()) {
        "martial arts" -> Art
        "cardio", "running", "run" -> Jade
        "qigong", "yoga", "breath", "mobility", "flexibility" -> Mist
        else -> Iron
    }
}

/** 4dp spacing grid. */
object InkSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val hero = 48.dp
}

object InkShapes {
    /** Cards and sheets. */
    val card = RoundedCornerShape(10.dp)

    /** Chips, pills, small controls. */
    val chip = RoundedCornerShape(6.dp)

    /**
     * The seal — a hand-cut square. Slightly uneven corners so buttons read
     * as stamped, not machined.
     */
    val seal = RoundedCornerShape(
        topStart = 9.dp,
        topEnd = 4.dp,
        bottomEnd = 11.dp,
        bottomStart = 5.dp,
    )

    /** Mirrored seal for variety (e.g. secondary stamp chips). */
    val sealAlt = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 10.dp,
        bottomEnd = 5.dp,
        bottomStart = 12.dp,
    )
}

private val LiliangColorScheme = darkColorScheme(
    primary = InkColors.Vermillion,
    onPrimary = InkColors.Bone,
    primaryContainer = InkColors.VermillionDeep,
    onPrimaryContainer = InkColors.Bone,
    secondary = InkColors.Gold,
    onSecondary = InkColors.Paper,
    tertiary = InkColors.InkWash,
    onTertiary = InkColors.Bone,
    background = InkColors.Paper,
    onBackground = InkColors.Bone,
    surface = InkColors.PaperRaised,
    onSurface = InkColors.Bone,
    surfaceVariant = InkColors.PaperHigh,
    onSurfaceVariant = InkColors.BoneDim,
    surfaceContainer = InkColors.PaperRaised,
    surfaceContainerHigh = InkColors.PaperHigh,
    surfaceContainerHighest = InkColors.PaperHigh,
    surfaceContainerLow = InkColors.PaperRaised,
    surfaceContainerLowest = InkColors.Paper,
    outline = InkColors.Line,
    outlineVariant = InkColors.Line,
    error = Color(0xFFD96A5B),
    onError = InkColors.Paper,
)

/**
 * Root theme. Bridges the ink palette into MaterialTheme so any stock M3
 * primitive (text fields, switches, dialogs) inherits the look, and
 * provides [LocalMotionEnabled] so decorative animation can be globally
 * disabled when the system animator scale is 0.
 */
@Composable
fun LiliangTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme() // dark-only by design
    val motionEnabled = rememberMotionEnabled()
    CompositionLocalProvider(LocalMotionEnabled provides motionEnabled) {
        MaterialTheme(
            colorScheme = LiliangColorScheme,
            typography = liliangMaterialTypography(),
            content = content,
        )
    }
}

/**
 * Carved-wood gradients for the hanging-menu language — beams, plaques,
 * tags. Procedural stand-ins with the grain implied by a vertical falloff;
 * one vocabulary shared by every surface that hangs something.
 */
object InkWood {
    /** The lintel — darkest, structural. */
    val Beam = Brush.verticalGradient(listOf(Color(0xFF34281A), Color(0xFF140F0A)))

    /** A hanging tablet. */
    val Plaque = Brush.verticalGradient(listOf(Color(0xFF352819), Color(0xFF281E12), Color(0xFF19120B)))

    /** The centre tablet — warmer, catches more light. */
    val PlaquePrime = Brush.verticalGradient(listOf(Color(0xFF46351F), Color(0xFF332515), Color(0xFF20170D)))
}
