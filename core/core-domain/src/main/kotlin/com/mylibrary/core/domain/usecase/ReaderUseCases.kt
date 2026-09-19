package com.mylibrary.core.domain.usecase

import com.mylibrary.core.common.AppResult
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.ReadingPosition
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.DocumentRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Opens a book and records that it was opened.
 *
 * Marking the book as opened is what drives "recently read" ordering. Doing it here — once, on the
 * single path every way of opening a book goes through — means no caller can forget to.
 */
class OpenBookUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val libraryRepository: LibraryRepository,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(book: Book, password: String? = null): AppResult<OpenDocument> {
        val result = documentRepository.open(book, password)
        if (result is AppResult.Success) {
            withContext(dispatchers.io) { libraryRepository.markOpened(book.id) }
        }
        return result
    }
}

/**
 * Persists where the reader is.
 *
 * Called on every page turn and every scroll settle, so it stays cheap: one upsert on the IO
 * dispatcher, with no read-modify-write of neighbouring rows.
 */
class SaveReadingProgressUseCase @Inject constructor(
    private val progressRepository: ReadingProgressRepository,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(
        bookId: Long,
        locator: ReadingLocator,
        percent: Float,
        excerpt: String? = null,
    ) = withContext(dispatchers.io) {
        progressRepository.savePosition(
            ReadingPosition(
                bookId = bookId,
                locator = locator,
                percent = percent.coerceIn(0f, 1f),
                excerpt = excerpt,
            ),
        )
    }
}

/** The saved position to resume a book from, or `null` if it has never been read. */
class RestoreReadingPositionUseCase @Inject constructor(
    private val progressRepository: ReadingProgressRepository,
) {
    suspend operator fun invoke(bookId: Long): ReadingPosition? =
        progressRepository.observePosition(bookId).first()
}

/** Bookmarks for one book, observed for the bookmarks list. */
class ObserveBookmarksUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
) {
    operator fun invoke(bookId: Long): Flow<List<Bookmark>> =
        bookmarkRepository.observeBookmarks(bookId)
}

/**
 * Creates the bookmark at a position, or removes it if one is already there.
 *
 * "Toggle" rather than separate add and remove because the reader's toolbar has one bookmark
 * button: the UI asks for the state it wants and never has to track whether one already exists.
 */
class ToggleBookmarkUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * @return `true` if a bookmark now exists at [locator], `false` if it was removed.
     */
    suspend operator fun invoke(
        bookId: Long,
        locator: ReadingLocator,
        label: String? = null,
        excerpt: String? = null,
        colorArgb: Int? = null,
        note: String? = null,
    ): Boolean = withContext(dispatchers.io) {
        val existing = bookmarkRepository.observeBookmarks(bookId)
            .first()
            .firstOrNull { it.locator == locator }

        if (existing != null) {
            bookmarkRepository.deleteBookmark(existing.id)
            false
        } else {
            bookmarkRepository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    locator = locator,
                    label = label,
                    excerpt = excerpt,
                    colorArgb = colorArgb,
                    note = note,
                ),
            )
            true
        }
    }
}

/** Deletes a bookmark or highlight. */
class DeleteBookmarkUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(bookmarkId: Long) =
        withContext(dispatchers.io) { bookmarkRepository.deleteBookmark(bookmarkId) }
}

/** Saves a note against an existing bookmark. */
class SaveBookmarkNoteUseCase @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(bookmark: Bookmark, note: String) = withContext(dispatchers.io) {
        bookmarkRepository.updateBookmark(bookmark.copy(note = note.ifBlank { null }))
    }
}

/**
 * Computes how far through a document a locator is, in 0f..1f.
 *
 * A use case rather than a UI helper because the reader, the library card and the bookmarks list
 * must all agree: if the reader says 40% the library card must not say 12%.
 *
 * Note the deliberate asymmetry: a page index is an exact position, so page *n* of *N* is honest
 * progress. A chapter index is not — chapters vary from one page to a hundred — so progress within
 * a reflowable book is reported per chapter and refined by the reader with the character offset
 * when it has one.
 */
class ReadingProgressUseCase @Inject constructor() {

    /**
     * @param fractionWithinChapter how far into the chapter the reader is, in 0f..1f. Zero for a
     *   chapter that has not been paginated — a scrolling reader has no such fraction to give, and
     *   reporting one it had invented would be worse than reporting none.
     */
    operator fun invoke(
        locator: ReadingLocator,
        document: OpenDocument,
        fractionWithinChapter: Float = 0f,
    ): Float = when (document) {
        is PagedDocument -> when (locator) {
            is ReadingLocator.Paged -> fromPage(locator.pageIndex, document.pageCount)
            // A reflowable locator against a paged document means the position was saved against
            // a different rendering of the book; report "start" rather than a wrong percentage.
            is ReadingLocator.Reflowable -> 0f
        }

        is ReflowableDocument -> when (locator) {
            is ReadingLocator.Reflowable -> fromChapter(
                chapterIndex = locator.chapterIndex,
                chapterCount = document.chapterCount,
                fractionWithinChapter = fractionWithinChapter,
            )

            is ReadingLocator.Paged -> 0f
        }

        else -> 0f
    }

    /** Exact progress for a paged document, from a page index. */
    fun fromPage(pageIndex: Int, pageCount: Int): Float {
        if (pageCount <= 0) return 0f
        return ((pageIndex + 1).toFloat() / pageCount).coerceIn(0f, 1f)
    }

    /**
     * Progress for a reflowable document, combining the chapter index with how far into the
     * chapter the reader is.
     */
    fun fromChapter(chapterIndex: Int, chapterCount: Int, fractionWithinChapter: Float = 0f): Float {
        if (chapterCount <= 0) return 0f
        val withinChapter = fractionWithinChapter.coerceIn(0f, 1f)
        return ((chapterIndex + withinChapter) / chapterCount).coerceIn(0f, 1f)
    }
}
