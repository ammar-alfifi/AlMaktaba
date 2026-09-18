package com.mylibrary.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.usecase.BookDetails
import com.mylibrary.core.domain.usecase.DeleteBookmarkUseCase
import com.mylibrary.core.domain.usecase.ObserveBookUseCase
import com.mylibrary.core.domain.usecase.ToggleFavoriteUseCase
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** The book details screen's single state object. */
@Immutable
data class BookDetailsUiState(
    val isLoading: Boolean = true,
    val details: BookDetails? = null,
) {
    val bookTitle: String get() = details?.book?.title.orEmpty()
}

sealed interface BookDetailsIntent {
    data object Read : BookDetailsIntent
    data object ToggleFavorite : BookDetailsIntent
    data class DeleteBookmark(val bookmarkId: Long) : BookDetailsIntent

    /** Puts back a bookmark deleted here, from the undo action on the snackbar. */
    data class UndoDeleteBookmark(val bookmark: Bookmark) : BookDetailsIntent
    data object Deleted : BookDetailsIntent
}

sealed interface BookDetailsEffect {
    data class OpenReader(val bookId: Long) : BookDetailsEffect
    data object NavigateBack : BookDetailsEffect
    data object Deleted : BookDetailsEffect

    /**
     * A bookmark was deleted. Carries the bookmark so the screen can offer undo — re-adding needs
     * the whole object, not the id it was deleted by.
     */
    data class BookmarkDeleted(val bookmark: Bookmark) : BookDetailsEffect
}

/**
 * Drives the book details screen.
 *
 * `bookId` comes from the navigation arguments via [SavedStateHandle] rather than being pushed in
 * from the UI, so the screen survives process death and recreation without the caller having to
 * re-supply it.
 */
@HiltViewModel
class BookDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeBook: ObserveBookUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val deleteBookmark: DeleteBookmarkUseCase,
    private val bookmarkRepository: BookmarkRepository,
) : MviViewModel<BookDetailsUiState, BookDetailsIntent, BookDetailsEffect>(BookDetailsUiState()) {

    private val bookId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_BOOK_ID)) {
        "BookDetailsViewModel requires a '$ARG_BOOK_ID' navigation argument"
    }

    init {
        launch {
            observeBook(bookId)
                .map { details -> BookDetailsUiState(isLoading = false, details = details) }
                .onStart { setState { copy(isLoading = true) } }
                // A failure here means the row is gone — the book was deleted — which the screen
                // renders as "not found" rather than as a crash.
                .catch { setState { copy(isLoading = false, details = null) } }
                .collect { next -> setState { next } }
        }
    }

    override fun onIntent(intent: BookDetailsIntent) {
        when (intent) {
            BookDetailsIntent.Read -> emit(BookDetailsEffect.OpenReader(bookId))
            BookDetailsIntent.ToggleFavorite -> {
                val book = currentState.details?.book ?: return
                launch { toggleFavorite(book.id, !book.isFavorite) }
            }

            is BookDetailsIntent.DeleteBookmark -> launch {
                // The whole bookmark is captured before deleting, because undo re-adds it — an id
                // alone cannot bring back a label, a colour or an excerpt.
                val bookmark = currentState.details?.bookmarks
                    ?.firstOrNull { it.id == intent.bookmarkId }
                    ?: return@launch
                deleteBookmark(bookmark.id)
                emit(BookDetailsEffect.BookmarkDeleted(bookmark))
            }

            is BookDetailsIntent.UndoDeleteBookmark -> launch {
                bookmarkRepository.addBookmark(intent.bookmark)
            }
            BookDetailsIntent.Deleted -> emit(BookDetailsEffect.Deleted)
        }
    }

    private fun emit(effect: BookDetailsEffect) {
        launch { sendEffect(effect) }
    }

    companion object {
        const val ARG_BOOK_ID = "bookId"
    }
}
