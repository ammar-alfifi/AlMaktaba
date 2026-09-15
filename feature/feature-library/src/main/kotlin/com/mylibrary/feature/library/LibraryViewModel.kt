package com.mylibrary.feature.library

import androidx.lifecycle.viewModelScope
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.usecase.DeleteBooksUseCase
import com.mylibrary.core.domain.usecase.ImportBooksUseCase
import com.mylibrary.core.domain.usecase.ImportCandidate
import com.mylibrary.core.domain.usecase.ObserveLibraryUseCase
import com.mylibrary.core.domain.usecase.ObserveSettingsUseCase
import com.mylibrary.core.domain.usecase.ToggleFavoriteUseCase
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drives the library screen.
 *
 * Sort order and view mode are read from — and written back to — the settings store rather than
 * held as screen-local state. That is what makes the user's choice of a list layout survive leaving
 * the screen, closing the app and a device restart, which is what people expect of a control they
 * reach from the toolbar.
 *
 * The screen's own state holds only what is genuinely transient: which filters are active and which
 * book's context menu is open.
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
) : MviViewModel<LibraryUiState, LibraryIntent, LibraryEffect>(LibraryUiState()) {

    init {
        observeLibraryContent()
        observeCountsAndFormats()
        observePreferences()
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
        ) { sort, favoritesOnly, formats -> LibraryCriteria(sort, favoritesOnly, formats) }

        viewModelScope.launch {
            criteria.collectLatest { (sort, favoritesOnly, formats) ->
                observeLibrary(
                    sort = sort,
                    favoritesOnly = favoritesOnly,
                    formats = formats,
                ).collect { items -> setState { copy(items = items) } }
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
        setState { copy(isImporting = true) }
        launch {
            val summary = importBooks(candidates)
            setState { copy(isImporting = false) }
            emit(LibraryEffect.ShowMessage(LibraryMessage.ImportFinished(summary)))
        }
    }

    private fun delete(bookIds: List<Long>) {
        setState { copy(menuTarget = null) }
        launch {
            deleteBooks(bookIds)
            val message = if (bookIds.size == 1) LibraryMessage.BookDeleted else LibraryMessage.BooksDeleted
            emit(LibraryEffect.ShowMessage(message))
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
)
