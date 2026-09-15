package com.mylibrary.feature.library

import androidx.compose.runtime.Immutable
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.usecase.ImportCandidate
import com.mylibrary.core.domain.usecase.ImportSummary

/**
 * Everything the library screen renders, as one value.
 *
 * Note what is *not* here: no `isLoading` alongside a nullable list, and no separate
 * `showEmptyState` boolean. Those arrangements let a screen end up simultaneously loading and
 * showing an error. Instead, "nothing imported yet" is expressed by [items] being empty and
 * [isImporting] being false, and the screen derives its appearance from that one combination.
 */
@Immutable
data class LibraryUiState(
    val items: List<LibraryItem> = emptyList(),
    val sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
    val viewMode: ViewMode = ViewMode.GRID,
    val favoritesOnly: Boolean = false,
    val formatFilter: Set<BookFormat> = emptySet(),
    /** Formats actually present in the library, so the filter chips never offer an empty result. */
    val availableFormats: Set<BookFormat> = emptySet(),
    val isImporting: Boolean = false,
    val totalBookCount: Int = 0,
    /** The book whose context menu is open, if any. */
    val menuTarget: Book? = null,
    val error: AppError? = null,
) {
    /**
     * True only once the library is genuinely known to be empty.
     *
     * This is deliberately not `items.isEmpty()`: while the first database emission is still in
     * flight the list is also empty, and showing "add your first book" for a frame before a
     * hundred books appear is worse than showing nothing.
     */
    val isEmptyLibrary: Boolean get() = totalBookCount == 0 && !isImporting

    /** True when a filter is hiding books the library actually contains. */
    val isFilteredWithoutMatches: Boolean
        get() = items.isEmpty() && totalBookCount > 0
}

/** Every action the library screen can produce. */
sealed interface LibraryIntent {
    data class ImportPicked(val candidates: List<ImportCandidate>) : LibraryIntent
    data class SortChanged(val sort: LibrarySort) : LibraryIntent
    data class BookOpened(val bookId: Long) : LibraryIntent
    data class DetailsRequested(val bookId: Long) : LibraryIntent
    data object ToggleViewMode : LibraryIntent
    data object ToggleFavoritesFilter : LibraryIntent
    data class ToggleFormatFilter(val format: BookFormat) : LibraryIntent
    data class BookLongPressed(val book: Book) : LibraryIntent
    data object DismissMenu : LibraryIntent
    data class ToggleFavorite(val book: Book) : LibraryIntent
    data class DeleteBooks(val bookIds: List<Long>) : LibraryIntent
    data object DismissError : LibraryIntent
}

/** One-shot outcomes that must not be re-delivered on recomposition. */
sealed interface LibraryEffect {
    data class OpenBook(val bookId: Long) : LibraryEffect
    data class OpenDetails(val bookId: Long) : LibraryEffect
    data class ShowMessage(val message: LibraryMessage) : LibraryEffect
}

/**
 * Messages the screen can show.
 *
 * A closed type rather than a `String`, so the ViewModel never formats user-facing text — the
 * composable resolves it against the current locale, which is what keeps an Arabic UI Arabic even
 * though the import happened on a background thread with no locale of its own.
 */
sealed interface LibraryMessage {
    data class ImportFinished(val summary: ImportSummary) : LibraryMessage
    data object ImportFailed : LibraryMessage
    data object BookDeleted : LibraryMessage
    data object BooksDeleted : LibraryMessage
}
