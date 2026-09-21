package com.mylibrary.core.domain.model

/** Which colour scheme the app uses. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Where the app's colours come from.
 *
 * One setting rather than the "dynamic colour" switch this replaces, because "which palette" and
 * "follow the wallpaper" are the same question, and two controls for one decision are two places to
 * look for it — and two ways for the app to be told something it cannot honour.
 *
 * Every entry except [WALLPAPER] names a Material 3 scheme generated from a seed colour by
 * `tools/material_palette.py` and shipped as constants; [WALLPAPER] is the device's own palette,
 * which can only be read at runtime. [TEAL] is the app's original hand-authored scheme.
 *
 * Entries are only ever appended: the choice is persisted by name, and the settings store resolves
 * an unknown name to the default rather than failing, so inserting one in the middle would not
 * corrupt anything — but appending keeps the picker's order stable for anyone who has learnt it.
 */
enum class ColorSource {
    /**
     * Material You: the palette derived from the device's wallpaper.
     *
     * Needs Android 12. Below that there is no wallpaper palette to read, and this resolves to
     * [TEAL] — which is why it is hidden from the picker entirely on those devices rather than
     * offered and then quietly ignored.
     */
    WALLPAPER,

    /** The app's own teal, hand-authored and unchanged since the first release. */
    TEAL,

    PURPLE,
    BLUE,
    GREEN,
    AMBER,
    ROSE,
    ;

    /** Whether this source has a palette that can be shown on the device it is being asked about. */
    fun isAvailable(supportsWallpaperColors: Boolean): Boolean =
        this != WALLPAPER || supportsWallpaperColors
}

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

/**
 * Font family used for reflowable text.
 *
 * The first four are the platform's own families, resolved by Android to Noto Naskh Arabic / Noto
 * Sans Arabic for Arabic text and Roboto for Latin. The last three are Arabic faces **bundled with
 * the app** (`:core:core-ui`'s `res/font`), which is a deliberate change of policy: the system
 * families are competent but generic, differ from device to device, and give a reader no way to
 * change the *voice* of a book. A bundled face renders identically everywhere and can be relied on
 * to exist, which is what makes it worth its size in the APK.
 *
 * Entries are only ever appended. The choice is persisted by name, so inserting one in the middle
 * would not corrupt anything — but appending keeps the picker's order stable for users who have
 * already learned it.
 */
enum class ReaderFont {
    SYSTEM,
    SERIF,
    SANS_SERIF,
    MONOSPACE,

    /** Amiri — a Naskh revival in the tradition of Bulaq press types. The reading face. */
    AMIRI,

    /** IBM Plex Sans Arabic — a contemporary, unornamented sans. */
    PLEX_ARABIC,

    /** Reem Kufi — a geometric Kufic, for headings and for setting a book's title apart. */
    REEM_KUFI,
}

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
/**
 * How a document is presented: as discrete pages, or as a continuous scroll.
 *
 * One setting for both families of file, because it is one question. A comic and a novel may
 * genuinely want different answers, but they want them from the *same* control in the same place —
 * two settings meaning "pages or scrolling" gave the reader two places to look for one decision and
 * let one of them be wired to nothing.
 *
 * The entry names are load-bearing: the settings store resolves a stored value by name
 * (`SettingsDataStore`), so renaming the enum is safe and renaming `SCROLL` or `PAGED` is not.
 */
enum class ReaderLayout {
    /** One page at a time, turned by a swipe or a tap. */
    PAGED,

    /** Pages one below the other, scrolled through continuously. */
    SCROLL,
}

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
 * How a page is animated as it is turned.
 *
 * Three effects rather than a gallery of them. These are the three a reader actually chooses
 * between — the feel of paper, the feel of a swipe, and a minimum of movement — and every one of
 * them is driven by the finger's own position rather than played after the fact, which is what
 * makes a turn feel connected to the gesture instead of merely following it.
 */
enum class PageTurnEffect {
    /**
     * The page swings away about its binding edge under a moving shadow, revealing the next.
     *
     * The default: it is the only one of the three that says *where* the page went, which is the
     * thing a reader glancing up mid-gesture needs to know.
     */
    CURL,

    /** The page slides aside with a slight lift and the next comes forward from behind it. */
    SLIDE,

    /** The page fades out as the next fades in, with almost no movement. */
    FADE,
}

/**
 * What the progress bar counts: the whole book, or the chapter the reader is in.
 *
 * Both are honest answers to "how far along am I", and which one is wanted depends on the book. A
 * novel read from front to back wants the book's own page number, because that is the number a
 * reader remembers and the one that says how much is left. A reference work, a collection of essays,
 * or a religious text read a chapter at a time wants the chapter's, because the position inside the
 * chapter being read is the one that changes as pages are turned, and "page 3 of 20" says more about
 * how long the sitting will be than "page 412 of 900" does.
 *
 * Counting the book is the default because it is what the bar already claimed to do: the percentage
 * beside it has always been the book's, so a counter that restarted at every chapter was the odd one
 * out. It is also the expensive one — the book has to be measured to know how long it is in pages —
 * which is why it can be turned off.
 */
enum class ProgressScope {
    /** The book's own page number: "page 124 of 340", continuing across every chapter. */
    BOOK,

    /** The page within the chapter being read: "page 3 of 20", restarting at each one. */
    CHAPTER,
}

/**
 * The direction pages advance in.
 *
 * [SYSTEM] derives the direction from the book's own language — an Arabic book reads right-to-left
 * even on an English device — while [LTR] and [RTL] force it.
 */
enum class ReadingDirection { SYSTEM, LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/**
 * How the lines of reflowable body text are aligned against the column.
 *
 * [START] follows the column's own direction — the right edge in an RTL column, the left in an LTR
 * one — which is what a book is normally set in and so is the default. [JUSTIFY] sets every line but
 * the last to the full column width, the printed-book look a reader may prefer on a narrow phone.
 */
enum class TextAlignment { START, CENTER, JUSTIFY }

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
    /** Which palette the app is drawn in. See [ColorSource]. */
    val colorSource: ColorSource = ColorSource.TEAL,
    /**
     * Whether the first-run colour setup has been answered.
     *
     * A first launch shows that screen; every launch after it goes straight to the shelf. It is
     * true for anyone whose settings file has anything in it, so an upgrade never sends an existing
     * reader through a welcome they have already had — see `SettingsDataStore`.
     */
    val setupComplete: Boolean = false,
    val language: AppLanguage = AppLanguage.ARABIC,
    val viewMode: ViewMode = ViewMode.GRID,
    val librarySort: LibrarySort = LibrarySort.RECENTLY_ADDED,

    // --- Reader defaults ---
    val readerFont: ReaderFont = ReaderFont.SYSTEM,
    /** Multiplier applied to the base body text size. 1.0 is the design default. */
    val fontScale: Float = 1.0f,
    /** Multiplier applied to line height. Arabic script needs more leading than Latin. */
    val lineHeightScale: Float = 1.0f,

    /**
     * Multiplier applied to the side margins of reflowed text.
     *
     * The margins are a page's own proportion rather than a fixed inset, so they are stored as a
     * multiplier on the reader's base margin exactly as the type is stored as a multiplier on the
     * base size. 1.0 is the margin the reader shipped with; larger is a narrower column with more
     * white around it, which is how a book is set, and smaller is what a reader who wants the
     * longest possible line on a phone will choose.
     *
     * It applies to the text, not to the page: a PDF or a comic is a picture of a page and is
     * fitted, not re-laid out, so this has nothing to say about it.
     */
    val marginScale: Float = 1.0f,

    /**
     * Multiplier applied to the space between paragraphs in reflowed text.
     *
     * Separate from [lineHeightScale], which opens up the leading *inside* a paragraph. The two are
     * different decisions — a reader can want loose lines and paragraphs that run on, or the
     * reverse — and a book set with no first-line indent needs the paragraph break to be visible in
     * the white space between blocks instead.
     *
     * Zero is a legitimate value: a document where every block is separated by a blank line of its
     * own does not need the reader to add another.
     */
    val paragraphSpacingScale: Float = 1.0f,

    /**
     * Whether the first line of a paragraph is indented.
     *
     * Off by default, because a reflowable document already says where its paragraphs begin — the
     * block list keeps them apart, and the spacing above is what a reader sees. On is for anyone who
     * wants the printed-book convention, which also makes a paragraph break legible with
     * [paragraphSpacingScale] turned all the way down.
     *
     * A switch rather than a slider: the indent is a convention, not a measurement, and the whole of
     * the choice is "in the manner of a printed book" or not. It is set relative to the text size,
     * so it stays a proportional indent at every font size.
     */
    val firstLineIndent: Boolean = false,

    /** How body text lines are aligned in the column. See [TextAlignment]. */
    val textAlign: TextAlignment = TextAlignment.START,

    val pageFitMode: PageFitMode = PageFitMode.PAGE,
    val readingDirection: ReadingDirection = ReadingDirection.SYSTEM,
    /** Keep the screen awake while a book is open. */
    val keepScreenOn: Boolean = true,
    /** Show the page number / progress indicator while reading. */
    val showProgressIndicator: Boolean = true,

    /**
     * Whether a document is read as pages or as a continuous scroll.
     *
     * One setting for every format. It used to be two — a reflow mode for text and a snapping switch
     * for page images — which meant the same decision had two homes, and the one belonging to page
     * images was wired to nothing at all.
     *
     * [ReaderLayout.PAGED] by default, so a new reader meets the app as a book: pages, turned. The
     * earlier default of a scrolling column was chosen when only text could scroll and the worry was
     * that pagination might cut a line; pages are now the presentation both families share, and the
     * scroll is one tap away for anyone who wants it.
     */
    val layout: ReaderLayout = ReaderLayout.PAGED,

    /**
     * Whether the page counter and the progress bar count the whole book or the current chapter.
     *
     * Only the reflowable paged reader has a choice to make here. A PDF or a comic already counts the
     * book, because a page *is* its unit, and a scrolling column has no pages to count at all — so a
     * setting that looks app-wide is honoured in exactly one place, which is why it is documented
     * rather than assumed. See [ProgressScope].
     */
    val progressScope: ProgressScope = ProgressScope.BOOK,

    /**
     * Whether tapping the sides of the page turns it.
     *
     * On by default, because it is the cheapest gesture a reader has and the middle of the screen
     * still reveals the toolbar. Off for anyone who would rather the whole surface toggled the
     * chrome and page turns came only from a swipe — a real preference, not a hypothetical one, and
     * the reason tap-to-turn is not baked in.
     */
    val tapToTurnPages: Boolean = true,

    /**
     * Whether a side tap goes the opposite way to the one it names.
     *
     * Separate from [readingDirection] on purpose, even though both end up deciding which side of the
     * screen moves forward. They answer different questions: the direction is about the *book* — an
     * Arabic comic's first page is on the right — while this is about the *hand*, and about a reader
     * who has learnt to tap one way and is not going to relearn it because a file's direction says
     * otherwise. Composed, they cover every combination without a second direction setting.
     */
    val reverseTapZones: Boolean = false,

    /** How a page is animated as it is turned. See [PageTurnEffect]. */
    val pageTurnEffect: PageTurnEffect = PageTurnEffect.CURL,

    /**
     * Whether turning a page or tapping a control in the reader ticks.
     *
     * On by default because a tick is what tells the reader the tap registered — the page-turn
     * effects are driven by the finger, and a gesture with no acknowledgment reads as dropped. Off
     * for anyone reading in bed beside someone asleep, or who simply finds the ticking noisy.
     */
    val hapticsEnabled: Boolean = true,

    /**
     * Whether a double-tap zooms into the speech bubble or panel under the finger.
     *
     * On by default, and only meaningful for documents made of page images — a comic, a manga, a
     * scanned PDF. It is the gesture that makes a phone a workable way to read a page that was
     * drawn for print: a bubble of dialogue is a few millimetres wide on a phone, and pinching to
     * read one line loses the panel it belongs to.
     *
     * Off restores the plain zoom, which is what a reader who wants to inspect artwork rather than
     * read dialogue would rather have.
     */
    val bubbleZoom: Boolean = true,
) {
    /**
     * The app's *own* settings back at their defaults, and nothing else.
     *
     * The three fields here are the ones the settings screen holds — the theme, the colour and the
     * language — and they are reset together because that screen is the interface's, and this is
     * what its "reset to defaults" means. What a book looks like is not in this list: the reader's
     * settings have their own reset, in the reader's panel, where the effect of it is visible on the
     * page.
     *
     * Defined here rather than written out in each of the two places that apply it — the use case
     * that persists it and the screen that shows it optimistically — because a reset that meant one
     * thing in the store and another in the UI would be a button whose effect visibly changed the
     * moment the store answered.
     */
    fun resetInterfaceDefaults(): ReaderSettings = copy(
        themeMode = Default.themeMode,
        colorSource = Default.colorSource,
        language = Default.language,
    )

    companion object {
        val Default = ReaderSettings()
    }
}
