package com.mylibrary.feature.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.ReflowMode
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.TocEntry

/** Which panel is open over the page, if any. Exactly one can be open at a time. */
enum class ReaderPanel { TABLE_OF_CONTENTS, BOOKMARKS, SETTINGS, SEARCH }

/**
 * The reader's single state object.
 *
 * The reader covers two very different document kinds, and rather than splitting into two screens
 * with two ViewModels — which would duplicate opening, progress, bookmarks, search and every
 * setting — the difference is reduced to three fields: [isPaged], [currentUnit] and [totalUnits],
 * where a "unit" is a page in one case and a chapter in the other. Everything else is shared, which
 * is why the toolbar, the sheets and the bookmark logic are written once.
 */
@Immutable
data class ReaderUiState(
    val isLoading: Boolean = true,
    val error: AppError? = null,

    /** An encrypted document, waiting for the user to supply a password. */
    val isAwaitingPassword: Boolean = false,
    val lastPasswordWasWrong: Boolean = false,

    val book: Book? = null,

    /**
     * What the open document can actually do.
     *
     * The reader serves five formats through four engines, and they do not offer the same things: a
     * comic has no text layer to search and no outline to navigate, a plain-text file has no outline
     * either, and only a PDF or a comic has pages to render. The toolbar reads this rather than
     * guessing from [isPaged], so an action that cannot work is absent instead of present and inert.
     */
    val capabilities: EngineCapabilities = EngineCapabilities(),

    val isPaged: Boolean = false,
    val totalUnits: Int = 0,
    val currentUnit: Int = 0,

    /** The label shown in the toolbar: a page counter or the current chapter's title. */
    val positionLabel: String? = null,

    /**
     * Where the reader is inside a paginated chapter, and how many pages that chapter came to.
     *
     * Only ever set by the paged view for a reflowable document; [reflowPageCount] stays zero in
     * scrolling mode, which is how the rest of the reader tells the two apart without asking the
     * settings — a chapter can be re-paginated while it is open, by a font-size change or a
     * rotation, and the count is the honest answer to "how long is this chapter" at that moment.
     */
    val reflowPage: Int = 0,
    val reflowPageCount: Int = 0,

    /**
     * The character offset in the chapter's text at which the current page begins.
     *
     * This is what makes a paged book survive being closed: a page number is meaningless after a
     * font-size change, while the character a page started at is still there, still in the same
     * place, and the page that now contains it is the one to reopen on.
     */
    val reflowOffset: Int = 0,

    val outline: List<TocEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),

    val settings: ReaderSettings = ReaderSettings.Default,

    /**
     * Whether the toolbars are showing.
     *
     * Reading is a full-screen activity: the chrome is hidden by default and revealed by a tap, so
     * the page is never reduced by a permanently visible toolbar.
     */
    val isChromeVisible: Boolean = false,
    val openPanel: ReaderPanel? = null,

    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,

    /**
     * True when the document's own language reads right-to-left.
     *
     * Kept separate from the app's layout direction: an English TXT file opened inside the Arabic UI
     * still reads left-to-right, and the *page* has to honour the document rather than the app.
     */
    val isRtlContent: Boolean = false,

    /**
     * An anchor waiting to be scrolled to, set when a link was just followed.
     *
     * The target chapter may not be laid out yet — following a footnote link crosses into a
     * different chapter — so the anchor is parked here and the renderer clears it once it has
     * scrolled to the block carrying it. Resolving the anchor eagerly would mean parsing a chapter
     * the reader is not looking at.
     */
    val pendingAnchor: String? = null,

    /**
     * Places the reader has jumped from by following a link, most recent last.
     *
     * Without this a footnote is a one-way trip: the reader lands at the end of the book with no way
     * back except scrubbing the progress bar. Popping restores the position that was on screen
     * before the jump.
     */
    val linkBackStack: List<ReadingLocator> = emptyList(),

    /**
     * The document's own body typeface, when it embeds one.
     *
     * Applied only while `ReaderSettings.readerFont` is `SYSTEM` — that setting already means "do
     * not override the text", and a publisher's face is what it resolves to when the document
     * carries one. Any explicit choice wins, so the user keeps control without a second control to
     * learn.
     */
    val documentFont: FontFamily? = null,
) {
    /**
     * How far through the document the reader is, in 0f..1f.
     *
     * Deliberately the same arithmetic as `ReadingProgressUseCase`, which is what the library card
     * and the bookmarks list show: if the reader's progress bar says 40% the shelf must not say 12%.
     * That is why the two kinds differ by one unit — a page index is a position the reader has
     * arrived at and finished (*n* of *N* is honest), while a chapter index is one they have only
     * just entered, so the chapters behind them are the ones that count. The reader has no
     * within-chapter scroll fraction to refine it with, and neither does the saved value.
     */
    val progress: Float
        get() = when {
            totalUnits <= 0 -> 0f
            isPaged -> (currentUnit + 1).toFloat() / totalUnits
            else -> (currentUnit + chapterFraction) / totalUnits
        }

    /**
     * How far through the current chapter the reader is, in 0f..1f.
     *
     * Zero while scrolling, because a scroll offset is not a fraction of anything until the text has
     * been laid out into pages — it is the paged view that knows how many pages the chapter made.
     * The saved percentage and the one on screen both come from this, so the shelf cannot disagree
     * with the book.
     */
    val chapterFraction: Float
        get() = if (reflowPageCount > 0) reflowPage.toFloat() / reflowPageCount else 0f

    val isBookmarked: Boolean
        get() = bookmarks.any { it.locator == currentLocator }

    /** True when a link was followed and there is somewhere to return to. */
    val canReturnFromLink: Boolean get() = linkBackStack.isNotEmpty()

    /** The locator for the position on screen, or `null` while the document is still opening. */
    val currentLocator: ReadingLocator?
        get() = when {
            book == null -> null
            isPaged -> ReadingLocator.Paged(currentUnit)
            else -> ReadingLocator.Reflowable(currentUnit, reflowOffset)
        }
}

sealed interface ReaderIntent {
    data object ToggleChrome : ReaderIntent
    data class OpenPanel(val panel: ReaderPanel) : ReaderIntent
    data object ClosePanel : ReaderIntent

    data class PageChanged(val pageIndex: Int) : ReaderIntent
    data class ChapterChanged(val chapterIndex: Int) : ReaderIntent

    /**
     * A reflowable chapter was re-paginated, or the reader moved within it.
     *
     * The paged view reports all three together because they are only meaningful together: the count
     * changes whenever the text is re-laid out, and the offset is where the page it just settled on
     * begins. Splitting them into three intents would let the state hold a page index from one
     * pagination beside a count from another.
     */
    data class ReflowPositionChanged(
        val pageIndex: Int,
        val pageCount: Int,
        val offset: Int,
    ) : ReaderIntent

    data class JumpTo(val locator: ReadingLocator) : ReaderIntent
    data object NextUnit : ReaderIntent
    data object PreviousUnit : ReaderIntent

    data object ToggleBookmark : ReaderIntent
    data class DeleteBookmark(val bookmarkId: Long) : ReaderIntent

    /** A link inside the document was tapped. The engine decides where it goes. */
    data class FollowLink(val href: String) : ReaderIntent

    /** Go back to where the reader was before the last link was followed. */
    data object ReturnFromLink : ReaderIntent

    /** The renderer has scrolled to a pending anchor; it no longer needs to be remembered. */
    data object AnchorReached : ReaderIntent

    data class SearchQueryChanged(val query: String) : ReaderIntent
    data object SubmitSearch : ReaderIntent
    data object ClearSearch : ReaderIntent

    data class SetThemeMode(val mode: ThemeMode) : ReaderIntent
    data class SetFont(val font: ReaderFont) : ReaderIntent
    data class SetFontScale(val scale: Float) : ReaderIntent
    data class SetLineHeight(val scale: Float) : ReaderIntent
    data class SetPageFit(val mode: PageFitMode) : ReaderIntent
    data class SetKeepScreenOn(val enabled: Boolean) : ReaderIntent
    data class SetReflowMode(val mode: ReflowMode) : ReaderIntent
    data class SetTapToTurnPages(val enabled: Boolean) : ReaderIntent
    data class SetPageTurnEffect(val effect: PageTurnEffect) : ReaderIntent
    data class SetBubbleZoom(val enabled: Boolean) : ReaderIntent

    /** Put the reading settings back to their defaults, after the reader has confirmed it. */
    data object RequestResetSettings : ReaderIntent

    data class PasswordSubmitted(val password: String) : ReaderIntent
    data object PasswordDismissed : ReaderIntent
    data object Retry : ReaderIntent
}

sealed interface ReaderEffect {
    data object NavigateBack : ReaderEffect
    data class ShowMessage(val message: ReaderMessage) : ReaderEffect

    /**
     * A link leaving the document. The screen hands it to the platform, which is the only layer that
     * can show a chooser or refuse a scheme — the ViewModel deliberately cannot.
     */
    data class OpenExternalUrl(val url: String) : ReaderEffect
}

/** One-shot reader messages, resolved to text by the screen so they are localized at render time. */
sealed interface ReaderMessage {
    data object BookmarkAdded : ReaderMessage
    data object BookmarkRemoved : ReaderMessage
    data object NoSearchResults : ReaderMessage
    data object SearchUnavailable : ReaderMessage

    /** Confirmation that the reading settings are back to their defaults. */
    data object SettingsReset : ReaderMessage

    /** A link that the engine could not resolve to anywhere in the document. */
    data object LinkUnavailable : ReaderMessage
}
