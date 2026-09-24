package com.mylibrary.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ThemeMode

/**
 * The single theme wrapper for every MyLibrary screen.
 *
 * The scheme comes from [ColorSource]: the device's wallpaper palette where there is one and the
 * reader asked for it, and one of the generated Material palettes otherwise — which is the whole
 * point of shipping six of them rather than only leaning on the wallpaper: the app looks deliberate
 * on a device too old to have Material You, and on one whose wallpaper makes a poor interface.
 *
 * The type scale is chosen from the *layout direction* rather than from the locale string, because
 * layout direction is what actually determines how the text will be shaped and wrapped. That also
 * means a forced-RTL reader view gets Arabic-appropriate leading even on an English device.
 */
@Composable
fun MyLibraryTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorSource: ColorSource = ColorSource.TEAL,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Resolved once per (source, brightness, device) rather than on every composition. The theme
    // recomposes whenever *any* setting changes — a font size dragged in the reader, say — and the
    // wallpaper palette is not free to build: `dynamicLightColorScheme` reads the system's colours
    // and allocates a full `ColorScheme`. None of that changes until the source, the brightness or
    // the Activity does, so it is remembered on exactly those.
    val colorScheme: ColorScheme = remember(
        colorSource,
        darkTheme,
        supportsDynamicColor,
        context,
    ) {
        wallpaperScheme(context, colorSource, darkTheme, supportsDynamicColor)
            ?: generatedColorScheme(colorSource, darkTheme)
            ?: if (darkTheme) TealDarkColors else TealLightColors
    }

    val layoutDirection = LocalLayoutDirection.current
    val typography = remember(layoutDirection) {
        myLibraryTypography(rtl = layoutDirection == LayoutDirection.Rtl)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = MyLibraryShapes,
        content = content,
    )
}

/**
 * The device's own palette, or `null` when there is none to be had.
 *
 * **Returning `null` rather than a fallback is the point.** A caller that cannot tell "the wallpaper
 * had nothing to say" from "here is the wallpaper's answer" would have no way to keep the two
 * fallbacks in the right order, and this is the one place that knows all three of the conditions:
 * the source must *be* the wallpaper, the device must be new enough to have Material You, and the
 * platform must actually produce a scheme. Dynamic colour below API 31 is not a degraded wallpaper
 * palette, it is no palette, and silently returning the default one here would make
 * [ColorSource.WALLPAPER] look supported on devices where the picker does not even offer it.
 */
private fun wallpaperScheme(
    context: android.content.Context,
    colorSource: ColorSource,
    darkTheme: Boolean,
    supportsDynamicColor: Boolean,
): ColorScheme? {
    if (colorSource != ColorSource.WALLPAPER || !supportsDynamicColor) return null
    return runCatching {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }.getOrNull()
}

/**
 * The light or dark scheme [source] stands for, without a device and without a wallpaper.
 *
 * Exists for the colour picker, which has to draw seven schemes at once on one screen and so cannot
 * go through [MyLibraryTheme]: the swatches are each drawn in the palette they name, while the
 * screen around them is drawn in the one being chosen. [ColorSource.WALLPAPER] resolves to the
 * app's own scheme here — it has no colours of its own to show until a device supplies them, and a
 * swatch that changed with the reader's wallpaper would be the one swatch that could not be
 * compared with the others.
 */
fun colorSchemeForSource(source: ColorSource, dark: Boolean): ColorScheme =
    generatedColorScheme(source, dark) ?: if (dark) TealDarkColors else TealLightColors

/**
 * The scheme [source] resolves to on *this* device, wallpaper palette and all.
 *
 * [colorSchemeForSource] deliberately cannot answer this: it is a plain function with no device to
 * read, and [ColorSource.WALLPAPER] has no fixed colours without one — it falls back to the app's
 * teal. A preview is different. It is drawn on a real device and its whole job is to show what will
 * actually be applied, so it reads the device's palette where there is one and falls back exactly as
 * [MyLibraryTheme] does. Without this the wallpaper swatch showed the app's teal beside the actual
 * palette the reader was about to get, which is the one colour a swatch must not lie about.
 */
@Composable
fun rememberColorSchemeForSource(source: ColorSource, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return remember(source, dark, supportsDynamicColor, context) {
        wallpaperScheme(context, source, dark, supportsDynamicColor)
            ?: generatedColorScheme(source, dark)
            ?: if (dark) TealDarkColors else TealLightColors
    }
}

/**
 * Overrides the layout direction for a subtree.
 *
 * Used by the reader: a reflowable document declares its own direction (an English TXT file read
 * inside the Arabic UI still reads left-to-right), and this lets the reader honour that for the
 * page content without changing the direction of the app's own chrome.
 */
@Composable
fun ProvideLayoutDirection(
    layoutDirection: LayoutDirection,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, content = content)
}

/**
 * True when the device's configuration says Arabic, used only for decisions that must be made
 * outside the composition (for example choosing an initial language).
 */
@Composable
fun isSystemArabic(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        // LocaleList is indexable rather than iterable on every supported API level, so this
        // walks it by index instead of using a collection operator.
        val locales = configuration.locales
        (0 until locales.size()).any { index -> locales[index].language == "ar" }
    }
}

/** Convenience for callers that hold a [ReaderSettings] rather than loose theme values. */
@Composable
fun MyLibraryTheme(
    settings: ReaderSettings,
    content: @Composable () -> Unit,
) {
    MyLibraryTheme(
        themeMode = settings.themeMode,
        colorSource = settings.colorSource,
        content = content,
    )
}
