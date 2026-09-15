package com.mylibrary.core.domain.model

/** Which colour scheme the app uses. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The UI language.
 *
 * [SYSTEM] follows the device. [ARABIC] and [ENGLISH] are explicit in-app overrides — Arabic is the
 * default for a fresh install, and changing this value applies the language *and* the layout
 * direction immediately, without restarting the app.
 */
enum class AppLanguage(val languageTag: String?) {
    ARABIC("ar"),
    ENGLISH("en"),
    SYSTEM(null),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.languageTag == tag } ?: SYSTEM
    }
}

/** Font family used for reflowable text. */
enum class ReaderFont { SYSTEM, SERIF, SANS_SERIF, MONOSPACE }

/** How a paged document is scaled into the viewport. */
enum class PageFitMode {
    /** Fit the page width, scrolling vertically for the rest of the page. */
    WIDTH,

    /** Fit the whole page on screen at once. */
    PAGE,

    /** No scaling: one page pixel per screen pixel. */
    ACTUAL_SIZE,
}

/**
 * The direction pages advance in.
 *
 * [SYSTEM] derives the direction from the book's own language — an Arabic book reads right-to-left
 * even on an English device — while [LTR] and [RTL] force it.
 */
enum class ReadingDirection { SYSTEM, LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/** How the library is laid out. */
enum class ViewMode { GRID, LIST }

/** How the library is ordered. */
enum class LibrarySort {
    RECENTLY_READ,
    RECENTLY_ADDED,
    TITLE_ASC,
    TITLE_DESC,
    AUTHOR,
}

/**
 * Everything the user can change about how MyLibrary looks and reads.
 *
 * One immutable object covers app-wide appearance and reader defaults, so a settings change is a
 * single `copy()` and every screen observes the same `Flow`.
 */
data class ReaderSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Use the Android 12+ wallpaper-derived palette. Ignored below API 31 and when disabled. */
    val dynamicColor: Boolean = true,
    val language: AppLanguage = AppLanguage.ARABIC,
    val viewMode: ViewMode = ViewMode.GRID,
    val librarySort: LibrarySort = LibrarySort.RECENTLY_ADDED,

    // --- Reader defaults ---
    val readerFont: ReaderFont = ReaderFont.SYSTEM,
    /** Multiplier applied to the base body text size. 1.0 is the design default. */
    val fontScale: Float = 1.0f,
    /** Multiplier applied to line height. Arabic script needs more leading than Latin. */
    val lineHeightScale: Float = 1.0f,
    val pageFitMode: PageFitMode = PageFitMode.PAGE,
    val readingDirection: ReadingDirection = ReadingDirection.SYSTEM,
    /** Keep the screen awake while a book is open. */
    val keepScreenOn: Boolean = true,
    /** Show the page number / progress indicator while reading. */
    val showProgressIndicator: Boolean = true,
    /** Snap paged documents to one page at a time instead of continuous scrolling. */
    val pageSnapping: Boolean = true,
) {
    companion object {
        val Default = ReaderSettings()
    }
}
