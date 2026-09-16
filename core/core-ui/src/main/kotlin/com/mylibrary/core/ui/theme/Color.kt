package com.mylibrary.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * MyLibrary's own colour scheme, used whenever dynamic colour is off or unavailable.
 *
 * The palette is a deep teal with an amber accent — chosen to read as "library" rather than
 * "generic app": teal is calm over long reading sessions, and the amber is reserved for the one
 * thing worth interrupting a reader for (bookmarks and highlights).
 *
 * Every role Material 3 defines is specified, including the `surfaceContainer*` tonal steps. That
 * matters more than it looks: Material 3 draws cards, sheets, menus and the navigation bar from
 * those tones, so leaving them at their defaults would make those surfaces mismatch the brand
 * palette in dark mode.
 *
 * Both palettes were built so that every foreground/background pairing clears WCAG AA contrast,
 * including `onSurfaceVariant` on `surfaceVariant`, which is the pair used for secondary text and
 * the one most often shipped too light.
 */

// --- Light ---------------------------------------------------------------

internal val TealPrimaryLight = Color(0xFF0E6E62)
internal val OnPrimaryLight = Color(0xFFFFFFFF)

internal val LightColors = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = Color(0xFFA6F2E1),
    onPrimaryContainer = Color(0xFF00201B),

    inversePrimary = Color(0xFF8AD5C5),

    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E1),
    onSecondaryContainer = Color(0xFF06201B),

    tertiary = Color(0xFF8B5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF5FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4947),

    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFF2C3230),
    inverseOnSurface = Color(0xFFEDF2EF),

    surfaceDim = Color(0xFFD5DBD8),
    surfaceBright = Color(0xFFF5FBF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    // The container steps are deliberately deeper than Material 3's baseline. A filled card is
    // drawn on `surfaceContainerHighest`, and against a background only a shade away from white the
    // baseline step is nearly invisible: the settings screen read as one flat page with lines of
    // text on it rather than as a stack of cards. Deepening each step keeps the *ordering* Material
    // defines while making the separation visible, which is the whole job of these five roles.
    surfaceContainerLow = Color(0xFFEDF4F0),
    surfaceContainer = Color(0xFFE5EDE9),
    surfaceContainerHigh = Color(0xFFDDE6E2),
    surfaceContainerHighest = Color(0xFFD4DFDA),
)

// --- Dark ----------------------------------------------------------------

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD5C5),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFA6F2E1),

    inversePrimary = TealPrimaryLight,

    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1B3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCCE8E1),

    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6B3C00),
    onTertiaryContainer = Color(0xFFFFDCBE),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C5),

    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4947),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFFDEE4E1),
    inverseOnSurface = Color(0xFF2C3230),

    surfaceDim = Color(0xFF0F1513),
    surfaceBright = Color(0xFF353B39),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF262B29),
    surfaceContainerHighest = Color(0xFF303634),
)

/**
 * Highlight colours offered in the reader.
 *
 * Deliberately mid-tone and desaturated: these are drawn *behind* text, so a saturated colour
 * would destroy the contrast of the words on top of them.
 */
object HighlightColors {
    val Amber = Color(0xFFFFE082)
    val Mint = Color(0xFFB2DFDB)
    val Rose = Color(0xFFF8BBD0)
    val Sky = Color(0xFFB3E5FC)

    val All: List<Color> = listOf(Amber, Mint, Rose, Sky)

    /** ARGB ints, for persisting a highlight colour to the database. */
    val AllArgb: List<Int> = All.map { it.value.toLong().let { v -> (v and 0xFFFFFFFFL).toInt() } }
}
