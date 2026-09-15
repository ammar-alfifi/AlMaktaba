package com.mylibrary.feature.reader

import androidx.compose.runtime.Immutable
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingLocator
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

    val isPaged: Boolean = false,
    val totalUnits: Int = 0,
    val currentUnit: Int = 0,

    /** The label shown in the toolbar: a page counter or the current chapter's title. */
    val positionLabel: String? = null,

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
) {
    val progress: Float
        get() = if (totalUnits <= 0) 0f else (currentUnit + 1).toFloat() / totalUnits

    val isBookmarked: Boolean
        get() = bookmarks.any { it.locator == currentLocator }

    /** The locator for the position on screen, or `null` while the document is still opening. */
    val currentLocator: ReadingLocator?
        get() = when {
            book == null -> null
            isPaged -> ReadingLocator.Paged(currentUnit)
            else -> ReadingLocator.Reflowable(currentUnit, 0)
        }
}

sealed interface ReaderIntent {
    data object ToggleChrome : ReaderIntent
    data class OpenPanel(val panel: ReaderPanel) : ReaderIntent
    data object ClosePanel : ReaderIntent

    data class PageChanged(val pageIndex: Int) : ReaderIntent
    data class ChapterChanged(val chapterIndex: Int) : ReaderIntent
    data class JumpTo(val locator: ReadingLocator) : ReaderIntent
    data object NextUnit : ReaderIntent
    data object PreviousUnit : ReaderIntent

    data object ToggleBookmark : ReaderIntent
    data class DeleteBookmark(val bookmarkId: Long) : ReaderIntent

    data class SearchQueryChanged(val query: String) : ReaderIntent
    data object SubmitSearch : ReaderIntent
    data object ClearSearch : ReaderIntent

    data class SetThemeMode(val mode: ThemeMode) : ReaderIntent
    data class SetFont(val font: ReaderFont) : ReaderIntent
    data class SetFontScale(val scale: Float) : ReaderIntent
    data class SetLineHeight(val scale: Float) : ReaderIntent
    data class SetPageFit(val mode: PageFitMode) : ReaderIntent
    data class SetKeepScreenOn(val enabled: Boolean) : ReaderIntent

    data class PasswordSubmitted(val password: String) : ReaderIntent
    data object PasswordDismissed : ReaderIntent
    data object Retry : ReaderIntent
}

sealed interface ReaderEffect {
    data object NavigateBack : ReaderEffect
    data class ShowMessage(val message: ReaderMessage) : ReaderEffect
}

/** One-shot reader messages, resolved to text by the screen so they are localized at render time. */
sealed interface ReaderMessage {
    data object BookmarkAdded : ReaderMessage
    data object BookmarkRemoved : ReaderMessage
    data object NoSearchResults : ReaderMessage
    data object SearchUnavailable : ReaderMessage
}
