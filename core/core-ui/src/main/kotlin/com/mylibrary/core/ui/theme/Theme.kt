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
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ThemeMode

/**
 * The single theme wrapper for every MyLibrary screen.
 *
 * Dynamic colour is used on Android 12+ when the user has not turned it off, falling back to
 * [LightColors] / [DarkColors] everywhere else — which is the whole point of having a hand-built
 * palette rather than only the wallpaper one: the app looks deliberate on older devices instead of
 * looking like it forgot to style itself.
 *
 * The type scale is chosen from the *layout direction* rather than from the locale string, because
 * layout direction is what actually determines how the text will be shaped and wrapped. That also
 * means a forced-RTL reader view gets Arabic-appropriate leading even on an English device.
 */
@Composable
fun MyLibraryTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme: ColorScheme = when {
        dynamicColor && supportsDynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
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
        dynamicColor = settings.dynamicColor,
        content = content,
    )
}
