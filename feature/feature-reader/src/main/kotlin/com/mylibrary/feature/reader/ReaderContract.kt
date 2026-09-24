package com.mylibrary.feature.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.domain.model.ProgressScope
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.ReaderLayout
import com.mylibrary.core.domain.model.ReaderPaper
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.TextAlignment
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.TocEntry
import com.mylibrary.core.domain.usecase.FolderSequence

/** Which panel is open over the page, if any. Exactly one can be open at a time. */
enum class ReaderPanel { TABLE_OF_CONTENTS, BOOKMARKS, SETTINGS, SEARCH }

/**
 * A book of the open one's folder that the reader has opened ahead of needing it.
 *
 * It is a *book plus what its open document can say about itself* — how many units it has, and which
 * family it is drawn by — and deliberately not the document handle: the handle holds native state
 * (a pdfium document, an open archive) and belongs to the ViewModel, which is also the only thing
 * that can close it. The screen is handed the three facts it needs to draw the book's units and to
 * name it at a seam.
 */
@Immutable
data class ReaderSegment(
    val book: Book,
    /** Pages for a page-image book, chapters for a reflowed one — the unit its own reader counts. */
    val unitCount: Int,
    /** Whether this book is drawn as page images, and so which of the four presentations draws it. */
    val isPageImages: Boolean,
)

/**
 * The reader's single state object.
 *
 * The reader covers two very different document kinds, and rather than splitting into two screens
 * with two ViewModels — which would duplicate opening, progress, bookmarks, search and every
 * setting — the difference is reduced to three fields: [isPageImages], [currentUnit] and
 * [totalUnits], where a "unit" is a page in one case and a chapter in the other. Everything else is
 * shared, which is why the toolbar, the sheets and the bookmark logic are written once.
 *
 * **[isPageImages] is about the file; [hasPages] is about the reader.** They are different questions
 * and conflating them is what hid the page-turn control from a paged EPUB: the file decides whether
 * there are pixels to render and text to search, while the *layout* setting decides whether the
 * reader is turning pages at all.
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
     * guessing from [isPageImages], so an action that cannot work is absent instead of present and
     * inert.
     */
    val capabilities: EngineCapabilities = EngineCapabilities(),

    /** Whether the document is made of page images — a PDF or a comic — rather than reflowed text. */
    val isPageImages: Boolean = false,

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

    /**
     * How long every chapter of the open book is in pages, or `null` while it is being measured.
     *
     * Nothing here is counted from this until it is complete enough to be true: the reader falls back
     * to counting chapters, which is what it did before the book could be counted at all. It is
     * discarded the moment the text is laid out differently, because every number in it belongs to a
     * pagination that no longer exists — a font size, a margin, a rotation all replace it.
     */
    val bookIndex: BookPageIndex? = null,

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

    /**
     * The books either side of the open one, in the folder's own order.
     *
     * `null` for a book filed on its own, or one whose folder is no longer in the library — such a
     * reader simply ends where it always did, at the end of the document. What it *is* decides the
     * reader's order: a volume before this one and a volume after it are both drawn, above and
     * below, so the seam can be crossed in either direction rather than only forwards.
     */
    val sequence: FolderSequence? = null,

    /**
     * The book before the open one, open and ready to be drawn above it.
     *
     * Loaded before the reader reaches the seam rather than when they arrive at it, because a
     * document that has to be opened, parsed and decoded on the frame the reader crosses into it is
     * a stall in the middle of the one gesture this feature exists to make continuous.
     */
    val previousSegment: ReaderSegment? = null,

    /** The book after the open one, open and ready to be drawn below it. */
    val nextSegment: ReaderSegment? = null,

    /**
     * Neighbours the reader could not carry on into, by book id.
     *
     * A next volume waiting for a password, a file that will not open, or one this reader draws with
     * different machinery — a page of text inside a column of comic pages, or the reverse. All three
     * are things the reader learns only by trying, and all three end the same way: the seam between
     * the two books names the book and offers to open it as a book of its own, which is what the
     * reader would have done without any of this.
     */
    val unavailableNeighbours: Set<Long> = emptySet(),
) {
    /**
     * Whether the reader is showing discrete pages right now, whatever the document is made of.
     *
     * The layout setting, not the format: a PDF laid out as a continuous scroll has no pages to turn
     * and no page-turn effect to choose, and an EPUB laid out as pages has both. Every control that
     * only means something with pages in front of the reader asks this rather than asking what kind
     * of file is open.
     */
    val hasPages: Boolean get() = settings.layout == ReaderLayout.PAGED

    /**
     * How far through the document the reader is, in 0f..1f.
     *
     * Deliberately the same arithmetic as `ReadingProgressUseCase`, which is what the library card
     * and the bookmarks list show: if the reader's progress bar says 40% the shelf must not say 12%.
     * That is why the two kinds differ by one unit — a page index is a position the reader has
     * arrived at and finished (*n* of *N* is honest), while a chapter index is one they have only
     * just entered, so the chapters behind them are the ones that count.
     *
     * Once the book has been measured, a reflowable book counts its pages the same way a paged one
     * counts its own — *n* of *N*, the page being read counted as read — because it now has the same
     * thing to count with. It is a better answer than the chapter's: a book whose chapters are of
     * very different lengths advances by a whole chapter at every seam, so a hundred-page chapter and
     * a two-page one move the number by the same amount. Until the measurement is done, and whenever
     * the reader has asked to count chapters, the chapter's answer is given instead — which is
     * exactly the value this property had before a book could be measured at all. A scrolling column
     * gets that answer too, and has no within-chapter fraction to refine it with: a scroll offset is
     * not a fraction of anything until the text has been laid out into pages.
     */
    val progress: Float
        get() {
            if (totalUnits <= 0) return 0f
            val page = bookPageNumber
            val count = bookPageCount
            return when {
                page != null && count != null -> page.toFloat() / count
                isPageImages -> (currentUnit + 1).toFloat() / totalUnits
                else -> (currentUnit + chapterFraction) / totalUnits
            }
        }

    /**
     * Whether the reader is counting the book's pages rather than the current chapter's.
     *
     * Only ever true where there are pages to count and a setting asking for the book's: a paged
     * document counts the book already and has nothing to opt into, and a scrolling column has no
     * pages at all.
     */
    val countsBookPages: Boolean
        get() = hasPages && !isPageImages && settings.progressScope == ProgressScope.BOOK

    /**
     * The page's number in the whole book, counting from one, or `null` when it cannot be said.
     *
     * Both this and [bookPageCount] are `null` together — the pass measures the chapters behind the
     * reader before the ones ahead, so a number could be shown before the book's length is known, but
     * "page 124" with nothing after it is a different sentence from "page 124 of 340" and the reader
     * would watch it change. One answer, once it is whole. A chapter that paginated to nothing has no
     * page to number either, and answers `null` rather than the chapter's first.
     */
    val bookPageNumber: Int?
        get() = bookPageCount?.let { bookIndex?.pageNumber(currentUnit, reflowPage) }

    /** How many pages the whole book holds, or `null` until every chapter has been measured. */
    val bookPageCount: Int?
        get() = bookIndex
            ?.takeIf { countsBookPages && it.isComplete }
            ?.totalPages
            ?.takeIf { it > 0 }

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
            isPageImages -> ReadingLocator.Paged(currentUnit)
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
     * A reflowable chapter was re-paginated, or the reader moved within it — including *across* into
     * the chapter next to it, which is a turn like any other now.
     *
     * The paged view reports all four together because they are only meaningful together: the count
     * changes whenever the text is re-laid out, and the offset is where the page it just settled on
     * begins. Splitting them into separate intents would let the state hold a page index from one
     * pagination beside a count from another, or a page of one chapter beside another chapter's
     * number.
     *
     * [pageIndex] and [pageCount] are positions **within** [chapterIndex], not within the book: they
     * are what the toolbar's "page three of twenty" counts, and what the progress bar fills by.
     */
    data class ReflowPositionChanged(
        val chapterIndex: Int,
        val pageIndex: Int,
        val pageCount: Int,
        val offset: Int,
    ) : ReaderIntent

    data class JumpTo(val locator: ReadingLocator) : ReaderIntent
    data object NextUnit : ReaderIntent
    data object PreviousUnit : ReaderIntent

    /**
     * The reader has settled on a page of a book other than the open one.
     *
     * Sent by every presentation, in both directions, when the entry under the reader belongs to a
     * neighbour of the folder sequence — which can only happen after a seam, because the seam is the
     * only place two books meet. The ViewModel answers it by *becoming* that book: the file it is
     * drawing has been open since shortly before the reader got there, so the handover is a change of
     * which document the state describes rather than a second reader being started.
     *
     * [locator] is where in the new book the reader arrived, in the only form that survives that book
     * being laid out again — a page for a page-image book, a chapter and a character for a reflowed
     * one. A page *number* would have to be recomputed after a font-size change; the position does
     * not.
     */
    data class EnteredBook(val bookId: Long, val locator: ReadingLocator) : ReaderIntent

    /**
     * The reader asked to open a neighbouring book that could not be continued into.
     *
     * Only ever raised by the seam of a book the reader could not open ahead of time — one waiting
     * for a password, one whose file will not open, one drawn by different machinery. The position
     * in the book being left has to be written before the screen navigates away, and only the
     * ViewModel owns that, which is why this is an intent rather than navigation at the seam.
     */
    data class OpenNeighbour(val bookId: Long) : ReaderIntent

    /**
     * The whole book has been measured, or the measurement that was running is no longer about the
     * layout on screen.
     *
     * Sent by the paged view, which is the only thing that knows the width, the height and the text
     * styles a page is laid out at — and so the only thing that can say how long the book is in
     * pages. Clearing comes *first*, before the pass starts, so a font-size change leaves the reader
     * counting chapters rather than counting the pages of a layout that is gone.
     */
    data object BookPageIndexCleared : ReaderIntent
    data class BookPageIndexReady(val index: BookPageIndex) : ReaderIntent

    data object ToggleBookmark : ReaderIntent
    data class DeleteBookmark(val bookmarkId: Long) : ReaderIntent

    /** Puts back a bookmark this session deleted, from the undo action on the snackbar. */
    data class UndoDeleteBookmark(val bookmark: Bookmark) : ReaderIntent

    /** A link inside the document was tapped. The engine decides where it goes. */
    data class FollowLink(val href: String) : ReaderIntent

    /** Go back to where the reader was before the last link was followed. */
    data object ReturnFromLink : ReaderIntent

    /** The renderer has scrolled to a pending anchor; it no longer needs to be remembered. */
    data object AnchorReached : ReaderIntent

    data class SearchQueryChanged(val query: String) : ReaderIntent
    data object SubmitSearch : ReaderIntent

    /** Read the current chapter or page aloud. */
    data object ReadAloud : ReaderIntent

    /** Stop reading aloud. */
    data object StopReadingAloud : ReaderIntent

    data class SetThemeMode(val mode: ThemeMode) : ReaderIntent
    data class SetFont(val font: ReaderFont) : ReaderIntent
    data class SetFontScale(val scale: Float) : ReaderIntent
    data class SetLineHeight(val scale: Float) : ReaderIntent
    data class SetMargins(val scale: Float) : ReaderIntent
    data class SetParagraphSpacing(val scale: Float) : ReaderIntent
    data class SetFirstLineIndent(val enabled: Boolean) : ReaderIntent
    data class SetTextAlignment(val alignment: TextAlignment) : ReaderIntent
    data class SetPageFit(val mode: PageFitMode) : ReaderIntent
    data class SetKeepScreenOn(val enabled: Boolean) : ReaderIntent
    data class SetShowProgressIndicator(val enabled: Boolean) : ReaderIntent
    data class SetReaderPaper(val paper: ReaderPaper) : ReaderIntent
    data class SetLayout(val layout: ReaderLayout) : ReaderIntent
    data class SetProgressScope(val scope: ProgressScope) : ReaderIntent
    data class SetTapToTurnPages(val enabled: Boolean) : ReaderIntent
    data class SetReadingDirection(val direction: ReadingDirection) : ReaderIntent
    data class SetReverseTapZones(val enabled: Boolean) : ReaderIntent
    data class SetPageTurnEffect(val effect: PageTurnEffect) : ReaderIntent
    data class SetHapticsEnabled(val enabled: Boolean) : ReaderIntent
    data class SetBubbleZoom(val enabled: Boolean) : ReaderIntent

    /** Saves an edit to a bookmark's note or highlight colour, made from the bookmarks panel. */
    data class SaveBookmark(val bookmark: Bookmark) : ReaderIntent

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
     * Open a different book, at its first page.
     *
     * The screen navigates, because the ViewModel has no `NavController` and deliberately never
     * gets one. Starting at the beginning rather than at the saved position is the whole point of
     * the only caller — the end-of-volume panel — which exists to *begin* the next book: resuming a
     * book the reader has not yet started would drop them wherever a previous visit stopped.
     */
    data class OpenBook(val bookId: Long) : ReaderEffect

    /**
     * A link leaving the document. The screen hands it to the platform, which is the only layer that
     * can show a chooser or refuse a scheme — the ViewModel deliberately cannot.
     */
    data class OpenExternalUrl(val url: String) : ReaderEffect

    /**
     * Speak [text] aloud.
     *
     * A one-shot effect rather than state: the text of a chapter is large, and putting it in the UI
     * state would mean holding it for as long as the reader is open. The screen owns the platform
     * `TextToSpeech` engine, which is exactly the kind of thing this layer never touches.
     */
    data class Speak(val text: String) : ReaderEffect

    /** Stop speaking, if anything is being spoken. */
    data object StopSpeaking : ReaderEffect
}

/** One-shot reader messages, resolved to text by the screen so they are localized at render time. */
sealed interface ReaderMessage {
    data object BookmarkAdded : ReaderMessage
    data object BookmarkRemoved : ReaderMessage

    /**
     * A bookmark was deleted from the list. Carries the bookmark itself so the screen can offer
     * undo — re-adding needs the whole object, not just the id it was deleted by.
     */
    data class BookmarkDeleted(val bookmark: Bookmark) : ReaderMessage

    /** An edit to a bookmark's note or colour was saved. */
    data object BookmarkSaved : ReaderMessage

    data object NoSearchResults : ReaderMessage
    data object SearchUnavailable : ReaderMessage

    /** Read aloud was asked for a document or page that has no text to speak. */
    data object ReadAloudUnavailable : ReaderMessage

    /** Confirmation that the reading settings are back to their defaults. */
    data object SettingsReset : ReaderMessage

    /** A link that the engine could not resolve to anywhere in the document. */
    data object LinkUnavailable : ReaderMessage
}
