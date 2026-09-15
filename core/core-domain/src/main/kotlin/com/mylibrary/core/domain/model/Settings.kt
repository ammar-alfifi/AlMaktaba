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

/**
 * How a reflowable document — an EPUB or a plain-text file — is presented.
 *
 * [SCROLL] is one continuous column: the text moves under a still reader, and where they are in the
 * book is a chapter and a scroll offset that means nothing once the font size changes.
 *
 * [PAGED] splits the chapter into screen-sized pages that are turned like a paper book. It costs a
 * measurement pass over the chapter whenever the text is re-laid out — a font-size change, a
 * rotation, a new chapter — and it buys two things scrolling cannot: a page is a unit the reader can
 * hold in mind, and its position is a character offset that survives being reopened.
 */
enum class ReflowMode { SCROLL, PAGED }

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

    /**
     * Whether a reflowable document scrolls or is split into pages.
     *
     * [ReflowMode.SCROLL] by default: the reader has to work on a phone nobody has put this build on
     * yet, and a scrolling column cannot produce a blank page or a cut-off line, whatever the
     * measurement does. Paging is one tap away in the reader's own settings.
     */
    val reflowMode: ReflowMode = ReflowMode.SCROLL,

    /**
     * Whether tapping the sides of the page turns it.
     *
     * On by default, because it is the cheapest gesture a reader has and the middle of the screen
     * still reveals the toolbar. Off for anyone who would rather the whole surface toggled the
     * chrome and page turns came only from a swipe — a real preference, not a hypothetical one, and
     * the reason tap-to-turn is not baked in.
     */
    val tapToTurnPages: Boolean = true,
) {
    companion object {
        val Default = ReaderSettings()
    }
}
