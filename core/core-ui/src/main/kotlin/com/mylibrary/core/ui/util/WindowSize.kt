package com.mylibrary.core.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * The current window size, from AndroidX's own [WindowSizeClass].
 *
 * The width/height are taken from Compose's [LocalConfiguration] rather than from a `WindowManager`
 * metrics call, which has two consequences worth knowing:
 *
 *  - it works in `@Preview`, so a tablet layout can be previewed without an emulator; and
 *  - it tracks the *activity's* window, which updates on rotation, multi-window resize, and — on
 *    foldables — when the app is moved across the hinge, because the system re-delivers the
 *    configuration in each of those cases.
 *
 * The breakpoints are Material 3's (600dp medium, 840dp expanded) and come from the library's own
 * constants rather than being re-typed here.
 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        // BREAKPOINTS_V1 is the 600/840/1200dp set that Material 3's adaptive guidance uses,
        // and the one the predicates below are written against.
        WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
        )
    }
}

/**
 * True when there is room for a navigation rail beside the content instead of a bottom bar.
 *
 * 600dp is the point at which a phone-class layout stops being the right answer: a landscape
 * phone, most small tablets, and any foldable in its open state all clear it.
 */
val WindowSizeClass.showsNavigationRail: Boolean
    get() = isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * True on large tablets and desktop-class windows (840dp+).
 *
 * Used to widen content measures — a list of book covers stretched across a 1280dp window looks
 * broken, so the library grid caps its column width instead of filling the screen.
 */
val WindowSizeClass.isExpandedWidth: Boolean
    get() = isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

/**
 * True when the window is short — a phone in landscape, typically.
 *
 * The reader uses this to decide whether to offer the single-page or the two-page spread layout,
 * which is a height question, not a width one.
 */
val WindowSizeClass.isCompactHeight: Boolean
    get() = !isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
