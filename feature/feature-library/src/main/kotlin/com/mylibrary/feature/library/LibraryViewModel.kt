package com.mylibrary.feature.library

import androidx.lifecycle.viewModelScope
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.domain.engine.FolderScanner
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.usecase.DeleteBooksUseCase
import com.mylibrary.core.domain.usecase.DeleteFolderUseCase
import com.mylibrary.core.domain.usecase.FolderImportSummary
import com.mylibrary.core.domain.usecase.ImportBooksUseCase
import com.mylibrary.core.domain.usecase.ImportCandidate
import com.mylibrary.core.domain.usecase.ImportFolderUseCase
import com.mylibrary.core.domain.usecase.MoveBookToFolderUseCase
import com.mylibrary.core.domain.usecase.ObserveFoldersUseCase
import com.mylibrary.core.domain.usecase.ObserveLibraryUseCase
import com.mylibrary.core.domain.usecase.ObserveSettingsUseCase
import com.mylibrary.core.domain.usecase.RenameFolderUseCase
import com.mylibrary.core.domain.usecase.RescanFolderUseCase
import com.mylibrary.core.domain.usecase.ToggleFavoriteUseCase
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the library screen.
 *
 * Sort order and view mode are read from — and written back to — the settings store rather than
 * held as screen-local state. That is what makes the user's choice of a list layout survive leaving
 * the screen, closing the app and a device restart, which is what people expect of a control they
 * reach from the toolbar.
 *
 * The screen's own state holds only what is genuinely transient: which filters are active, which
 * folder is selected, and which menu is open.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val observeLibrary: ObserveLibraryUseCase,
    private val observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    private val importBooks: ImportBooksUseCase,
    private val deleteBooks: DeleteBooksUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val libraryRepository: LibraryRepository,
    private val observeFolders: ObserveFoldersUseCase,
    private val importFolder: ImportFolderUseCase,
    private val rescanFolder: RescanFolderUseCase,
    private val renameFolder: RenameFolderUseCase,
    private val deleteFolder: DeleteFolderUseCase,
    private val moveBookToFolder: MoveBookToFolderUseCase,
    private val folderScanner: FolderScanner,
    private val dispatchers: DispatcherProvider,
) : MviViewModel<LibraryUiState, LibraryIntent, LibraryEffect>(LibraryUiState()) {

    init {
        observeLibraryContent()
        observeContinueReading()
        observeCountsAndFormats()
        observePreferences()
        observeFolderList()
    }

    /**
     * Re-queries the library whenever the sort or a filter changes.
     *
     * `collectLatest` rather than `collect`: toggling a filter while the previous query is still
     * emitting cancels the stale one, so the list can never flash results for a filter the user has
     * already turned off.
     */
    private fun observeLibraryContent() {
        val criteria = combine(
            state.map { it.sort }.distinctUntilChanged(),
            state.map { it.favoritesOnly }.distinctUntilChanged(),
            state.map { it.formatFilter }.distinctUntilChanged(),
            state.map { it.folderFilter }.distinctUntilChanged(),
        ) { sort, favoritesOnly, formats, folderId ->
            LibraryCriteria(sort, favoritesOnly, formats, folderId)
        }

        viewModelScope.launch {
            criteria.collectLatest { (sort, favoritesOnly, formats, folderId) ->
                observeLibrary(
                    sort = sort,
                    favoritesOnly = favoritesOnly,
                    formats = formats,
                    folderId = folderId,
                ).collect { items -> setState { copy(items = items) } }
            }
        }
    }

    /**
     * Keeps the "continue reading" button pointing at what the open folder is up to.
     *
     * Re-queried whenever the folder selection changes — and on every change to the books or the
     * positions behind it, since [ObserveLibraryUseCase.continueReading] is itself a `Flow` — which
     * is what makes the button follow the chips rather than the order they were tapped in. It is not
     * derived from [LibraryUiState.items]: those are filtered by the favourites and format chips as
     * well, and a button that disappeared because the reader had filtered to comics would be a
     * different control from the one they asked for.
     */
    private fun observeContinueReading() {
        launch {
            state.map { it.folderFilter }
                .distinctUntilChanged()
                .collectLatest { folderId ->
                    observeLibrary.continueReading(folderId).collect { item ->
                        setState { copy(continueReading = item) }
                    }
                }
        }
    }

    private fun observeCountsAndFormats() {
        launch {
            libraryRepository.observeBookCount().collect { count ->
                setState { copy(totalBookCount = count) }
            }
        }
        launch {
            libraryRepository.observeAvailableFormats().collect { formats ->
                setState {
                    // Drop any selected format that no longer exists, so deleting the last CBZ
                    // cannot leave the list filtered by a chip that is no longer on screen.
                    copy(
                        availableFormats = formats,
                        formatFilter = formatFilter.intersect(formats),
                    )
                }
            }
        }
    }

    /** Mirrors persisted preferences into the UI, including changes made on other screens. */
    private fun observePreferences() {
        launch {
            observeSettings().collect { settings ->
                setState { copy(sort = settings.librarySort, viewMode = settings.viewMode) }
            }
        }
    }

    /**
     * Keeps the folder chips live, and marks the ones whose permission has lapsed.
     *
     * The availability check runs on every emission rather than once at startup because a grant can
     * be revoked while the app is running — by the user clearing the app's access in Settings, or by
     * the storage being unmounted — and a chip that cannot be read has to say so rather than filter
     * to an empty list and look like a bug.
     */
    private fun observeFolderList() {
        launch {
            observeFolders()
                // Asked off the main thread, and asked outside the reducer below: `hasPermission`
                // is a call into the system, and a state reducer that performs I/O is a reducer that
                // can stall the frame that applies it — or run twice under a compare-and-set retry.
                .map { folders -> folders to unavailableFolderIds(folders, ::isUnavailable) }
                .flowOn(dispatchers.io)
                .collect { (folders, unavailable) ->
                    setState {
                        copy(
                            folders = folders,
                            unavailableFolderIds = unavailable,
                            // The same rule the format chips follow: a filter that can no longer match
                            // anything is dropped rather than left selected over an empty list.
                            folderFilter = folderFilter?.takeIf { id -> folders.any { it.folder.id == id } },
                        )
                    }
                }
        }
    }

    private fun isUnavailable(summary: FolderSummary): Boolean =
        !folderScanner.hasPermission(summary.folder.uri)
    override fun onIntent(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.ImportPicked -> importPicked(intent.candidates)
            is LibraryIntent.SortChanged -> changeSort(intent.sort)
            is LibraryIntent.BookOpened -> emit(LibraryEffect.OpenBook(intent.bookId))
            is LibraryIntent.DetailsRequested -> emit(LibraryEffect.OpenDetails(intent.bookId))
            LibraryIntent.ToggleViewMode -> toggleViewMode()
            LibraryIntent.ToggleFavoritesFilter -> setState { copy(favoritesOnly = !favoritesOnly) }
            is LibraryIntent.ToggleFormatFilter -> toggleFormatFilter(intent.format)
            is LibraryIntent.BookLongPressed -> setState { copy(menuTarget = intent.book) }
            LibraryIntent.DismissMenu -> setState { copy(menuTarget = null) }
            is LibraryIntent.ToggleFavorite -> toggleFavoriteOf(intent.book)
            is LibraryIntent.DeleteBooks -> delete(intent.bookIds)
            LibraryIntent.DismissError -> setState { copy(error = null) }

            is LibraryIntent.ImportFolderPicked -> importPickedFolder(intent.treeUri)
            is LibraryIntent.FolderSelected -> selectFolder(intent.folderId)
            LibraryIntent.DismissFolderMenu -> Unit
            is LibraryIntent.RescanFolder -> rescan(intent.folderId)
            is LibraryIntent.RenameFolder -> rename(intent.folderId, intent.name)
            is LibraryIntent.DeleteFolder -> removeFolder(intent.folderId, intent.deleteContents)
            is LibraryIntent.MoveBookRequested -> setState {
                copy(menuTarget = null, moveTarget = intent.book)
            }
            is LibraryIntent.MoveBookToFolder -> move(intent.bookId, intent.folderId)
            LibraryIntent.DismissMoveSheet -> setState { copy(moveTarget = null) }

            is LibraryIntent.StartSelection -> setState {
                copy(menuTarget = null, isSelecting = true, selectedBookIds = setOf(intent.bookId))
            }
            is LibraryIntent.ToggleSelection -> setState {
                copy(
                    selectedBookIds = if (intent.bookId in selectedBookIds) {
                        selectedBookIds - intent.bookId
                    } else {
                        selectedBookIds + intent.bookId
                    },
                )
            }
            LibraryIntent.SelectAll -> setState {
                copy(selectedBookIds = items.map { it.book.id }.toSet())
            }
            LibraryIntent.ClearSelection -> setState {
                copy(isSelecting = false, selectedBookIds = emptySet())
            }
            LibraryIntent.DeleteSelected -> delete(currentState.selectedBookIds.toList())
            LibraryIntent.FavoriteSelected -> favoriteSelected()
        }
    }

    private fun changeSort(sort: LibrarySort) {
        setState { copy(sort = sort) }
        launch { updateSettings.setLibrarySort(sort) }
    }

    private fun toggleViewMode() {
        val next = if (currentState.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        setState { copy(viewMode = next) }
        launch { updateSettings.setViewMode(next) }
    }

    private fun toggleFormatFilter(format: BookFormat) {
        setState {
            copy(
                formatFilter = if (format in formatFilter) formatFilter - format else formatFilter + format,
            )
        }
    }

    private fun toggleFavoriteOf(book: Book) {
        setState { copy(menuTarget = null) }
        launch { toggleFavorite(book.id, !book.isFavorite) }
    }

    /**
     * Imports picked files.
     *
     * The import itself runs inside `ImportBooksUseCase`, which reads metadata and extracts covers
     * one book at a time; a failure for one file never aborts the rest, so whatever could be read is
     * added and the summary reports the rest.
     */
    private fun importPicked(candidates: List<ImportCandidate>) {
        if (candidates.isEmpty()) return
        setState { copy(isImportingFiles = true) }
        launch {
            val summary = importBooks(candidates)
            setState { copy(isImportingFiles = false) }
            emit(LibraryEffect.ShowMessage(LibraryMessage.ImportFinished(summary)))
        }
    }

    /**
     * Imports a device folder, and files what it contains under it.
     *
     * The folder's name is resolved on the IO dispatcher, not in the frame that receives the
     * picker's result: it is a query into the provider that handed back the tree, and on a provider
     * that is not local storage it can block for as long as the network takes. A frozen frame on
     * the one screen that has to stay responsive — the moment the user has just chosen a folder —
     * is the whole of this bug, and a name is not worth it. The bar goes up first so the tap has
     * feedback before the name arrives, and the name fills in beside it when it does.
     */
    private fun importPickedFolder(treeUri: String) {
        setState { copy(importingFolderName = "") }
        launch {
            runFolderImport(wasRescan = false) {
                val name = withContext(dispatchers.io) {
                    runCatching { folderScanner.displayName(treeUri) }.getOrNull().orEmpty()
                }
                setState { copy(importingFolderName = name) }
                importFolder(treeUri)
            }
        }
    }

    private fun rescan(folderId: Long) {
        val name = currentState.folders.firstOrNull { it.folder.id == folderId }?.folder?.name
        setState { copy(importingFolderName = name.orEmpty()) }
        launch {
            runFolderImport(wasRescan = true) { rescanFolder(folderId) }
        }
    }

    /**
     * Runs a folder scan and reports it, and — whatever happens — takes the progress bar down.
     *
     * The clear happens on every way out rather than only on success, because a scan that *throws*
     * used to leave [LibraryUiState.importingFolderName] set for the life of the screen: a bar that
     * never stops and a folder that never appears is indistinguishable from a frozen app, which is
     * how it gets reported. A failure now says so and puts the screen back, and cancellation — the
     * user leaving the screen mid-scan — clears the bar on its way out too.
     */
    private suspend fun runFolderImport(
        wasRescan: Boolean,
        import: suspend () -> FolderImportSummary,
    ) {
        try {
            val summary = import()
            setState { copy(importingFolderName = null) }
            reportFolderOutcome(summary, wasRescan)
        } catch (cancellation: CancellationException) {
            setState { copy(importingFolderName = null) }
            throw cancellation
        } catch (_: Exception) {
            setState { copy(importingFolderName = null) }
            sendEffect(LibraryEffect.ShowMessage(LibraryMessage.ImportFailed))
        }
    }

    /** One place that turns a folder scan's outcome into what the user is told about it. */
    private suspend fun reportFolderOutcome(summary: FolderImportSummary, wasRescan: Boolean) {
        if (summary.permissionDenied) {
            sendEffect(LibraryEffect.ShowMessage(LibraryMessage.FolderPermissionLost))
            return
        }
        sendEffect(
            LibraryEffect.ShowMessage(
                if (wasRescan) {
                    LibraryMessage.FolderRescanned(summary)
                } else {
                    LibraryMessage.FolderImported(summary)
                },
            ),
        )
        if (summary.truncated) {
            sendEffect(
                LibraryEffect.ShowMessage(LibraryMessage.FolderScanTruncated(FolderScanner.MAX_ENTRIES)),
            )
        }
    }

    private fun selectFolder(folderId: Long?) {
        // Tapping the selected folder clears the filter, which is what every chip row in the app
        // does and saves a trip to the "all books" chip.
        setState { copy(folderFilter = folderId?.takeIf { it != folderFilter }) }
    }

    private fun rename(folderId: Long, name: String) {
        if (name.isBlank()) return
        launch {
            renameFolder(folderId, name)
            emit(LibraryEffect.ShowMessage(LibraryMessage.FolderRenamed))
        }
    }

    private fun removeFolder(folderId: Long, deleteContents: Boolean) {
        val name = currentState.folders.firstOrNull { it.folder.id == folderId }?.folder?.name.orEmpty()
        setState { copy(folderFilter = folderFilter?.takeIf { it != folderId }) }
        launch {
            deleteFolder(folderId, deleteContents)
            emit(LibraryEffect.ShowMessage(LibraryMessage.FolderDeleted(name)))
        }
    }

    private fun move(bookId: Long, folderId: Long?) {
        val name = currentState.folders.firstOrNull { it.folder.id == folderId }?.folder?.name
        setState { copy(moveTarget = null) }
        launch {
            moveBookToFolder(bookId, folderId)
            emit(LibraryEffect.ShowMessage(LibraryMessage.BookMoved(name)))
        }
    }

    private fun delete(bookIds: List<Long>) {
        if (bookIds.isEmpty()) return
        // Leaving selection mode as the delete starts: the books it ticked are about to disappear,
        // and a mode still holding their ids would be selecting books that no longer exist.
        setState { copy(menuTarget = null, isSelecting = false, selectedBookIds = emptySet()) }
        launch {
            deleteBooks(bookIds)
            val message = if (bookIds.size == 1) LibraryMessage.BookDeleted else LibraryMessage.BooksDeleted
            emit(LibraryEffect.ShowMessage(message))
        }
    }

    /**
     * Adds every ticked book to the favourites, then leaves selection mode.
     *
     * One write per book rather than a bulk query: the favourite flag lives on the book row, and the
     * repository's `setFavorite` is the single place that keeps the library's live flow in step with
     * it. A handful of selected books is not a performance question.
     */
    private fun favoriteSelected() {
        val ids = currentState.selectedBookIds.toList()
        if (ids.isEmpty()) return
        setState { copy(isSelecting = false, selectedBookIds = emptySet()) }
        launch {
            ids.forEach { id -> toggleFavorite(id, true) }
            emit(LibraryEffect.ShowMessage(LibraryMessage.BooksFavorited))
        }
    }

    /** Sends a one-shot effect from a non-suspending caller. */
    private fun emit(effect: LibraryEffect) {
        launch { sendEffect(effect) }
    }
}

/** The tuple of criteria that decides which books the screen shows. */
private data class LibraryCriteria(
    val sort: LibrarySort,
    val favoritesOnly: Boolean,
    val formats: Set<BookFormat>,
    val folderId: Long?,
)

/**
 * The ids of the folders whose permission has lapsed.
 *
 * A pure function so the one thing that can go wrong here — marking the wrong set — is pinned by a
 * test rather than by inspecting a chip: `filterNot` in place of `filter` below marked every
 * *available* folder as unavailable, and the shelf believed it on every launch.
 */
internal fun unavailableFolderIds(
    folders: List<FolderSummary>,
    isUnavailable: (FolderSummary) -> Boolean,
): Set<Long> = folders.filter(isUnavailable).map { it.folder.id }.toSet()
