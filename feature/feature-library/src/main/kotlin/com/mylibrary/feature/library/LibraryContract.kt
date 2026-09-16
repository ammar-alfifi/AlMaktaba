package com.mylibrary.feature.library

import androidx.compose.runtime.Immutable
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.usecase.FolderImportSummary
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
    val isImportingFiles: Boolean = false,

    /** The device folders the user has added, with how many books each holds. */
    val folders: List<FolderSummary> = emptyList(),

    /**
     * Folders whose persisted permission is no longer held.
     *
     * Marked rather than hidden: a folder that silently disappeared from the shelf would look like
     * the app losing the reader's series, where a folder marked "unavailable" tells them to point at
     * it again — which restores it in one tap, since the row is matched by URI.
     */
    val unavailableFolderIds: Set<Long> = emptySet(),

    /** Which folder the library is filtered to, or `null` for the whole library. */
    val folderFilter: Long? = null,

    /** The folder currently being imported from or re-scanned, if any. */
    val importingFolderName: String? = null,

    val totalBookCount: Int = 0,
    /** The book whose context menu is open, if any. */
    val menuTarget: Book? = null,
    /** The book being moved between folders, if any. */
    val moveTarget: Book? = null,
    val error: AppError? = null,
) {
    /** True while any import — files or a folder — is running. */
    val isImporting: Boolean get() = isImportingFiles || importingFolderName != null

    /** The folder the library is filtered to, when it still exists. */
    val activeFolder: Folder? get() = folders.firstOrNull { it.folder.id == folderFilter }?.folder

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
    data class ImportFolderPicked(val treeUri: String) : LibraryIntent
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

    // --- Folders ---

    /** Filter the library to a folder, or to everything when [folderId] is `null`. */
    data class FolderSelected(val folderId: Long?) : LibraryIntent
    data object DismissFolderMenu : LibraryIntent
    data class RescanFolder(val folderId: Long) : LibraryIntent
    data class RenameFolder(val folderId: Long, val name: String) : LibraryIntent
    data class DeleteFolder(val folderId: Long, val deleteContents: Boolean) : LibraryIntent
    data class MoveBookRequested(val book: Book) : LibraryIntent
    data class MoveBookToFolder(val bookId: Long, val folderId: Long?) : LibraryIntent
    data object DismissMoveSheet : LibraryIntent
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

    data class FolderImported(val summary: FolderImportSummary) : LibraryMessage
    data class FolderRescanned(val summary: FolderImportSummary) : LibraryMessage
    data object FolderPermissionLost : LibraryMessage
    data object FolderRenamed : LibraryMessage
    data class FolderDeleted(val name: String) : LibraryMessage
    data class BookMoved(val folderName: String?) : LibraryMessage

    /** The import ran but the scan hit the ceiling, so not everything in the folder was read. */
    data class FolderScanTruncated(val limit: Int) : LibraryMessage
}
