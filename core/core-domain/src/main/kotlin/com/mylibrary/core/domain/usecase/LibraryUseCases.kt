package com.mylibrary.core.domain.usecase

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.common.fileStem
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ReadingPosition
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.DocumentRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The library as the UI needs it, ordered by [sort], with each book's reading progress attached.
 *
 * Books and progress are two separate queries combined in memory rather than joined in SQL: a
 * change to either stream re-emits immediately, and `:core:core-data` does not need to know that
 * the library screen cares about progress at all.
 */
class ObserveLibraryUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ReadingProgressRepository,
) {
    operator fun invoke(
        sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
        query: String = "",
        favoritesOnly: Boolean = false,
        formats: Set<BookFormat> = emptySet(),
    ): Flow<List<LibraryItem>> =
        combine(
            libraryRepository.observeBooks(sort, query),
            progressRepository.observeAllPositions(),
        ) { books, positions ->
            val progressByBook = positions.associateBy { it.bookId }
            books.asSequence()
                .filter { !favoritesOnly || it.isFavorite }
                .filter { formats.isEmpty() || it.format in formats }
                .map { book -> LibraryItem(book, progressByBook[book.id]) }
                .toList()
        }

    /**
     * The book to offer on the "continue reading" shelf: the most recently opened one that has not
     * been finished, falling back to the most recent book when everything is finished or new.
     */
    fun continueReading(): Flow<LibraryItem?> =
        invoke(sort = LibrarySort.RECENTLY_READ).map { items ->
            items.firstOrNull { it.position != null && !it.isFinished } ?: items.firstOrNull()
        }
}

/** Everything the book details screen shows about one book. */
data class BookDetails(
    val book: Book,
    val position: ReadingPosition?,
    val bookmarks: List<Bookmark>,
)

/** One book, observed with its progress and bookmarks for the details screen. */
class ObserveBookUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ReadingProgressRepository,
    private val bookmarkRepository: BookmarkRepository,
) {
    operator fun invoke(bookId: Long): Flow<BookDetails?> =
        combine(
            libraryRepository.observeBook(bookId),
            progressRepository.observePosition(bookId),
            bookmarkRepository.observeBookmarks(bookId),
        ) { book, position, bookmarks ->
            book?.let { BookDetails(it, position, bookmarks) }
        }
}

/** A file the user chose to import, described without reference to any Android type. */
data class ImportCandidate(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
)

/** What an import actually did, so the UI can report it honestly. */
data class ImportSummary(
    val imported: Int,
    val alreadyInLibrary: Int,
    val unsupported: Int,
) {
    val total: Int get() = imported + alreadyInLibrary + unsupported
}

/**
 * Adds picked files to the library.
 *
 * Import is deliberately cheap: the file is never copied, only its `content://` URI is stored, so
 * adding a 500 MB comic archive is instant. Format is decided from the file extension first and the
 * MIME type second, because providers routinely report a CBZ as plain `application/zip`.
 *
 * Metadata and cover extraction are best-effort. A book whose metadata cannot be read is still
 * imported — the reader can open it, and the title falls back to the file name.
 */
class ImportBooksUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val documentRepository: DocumentRepository,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(candidates: List<ImportCandidate>): ImportSummary =
        withContext(dispatchers.io) {
            var imported = 0
            var already = 0
            var unsupported = 0

            for (candidate in candidates) {
                val extension = candidate.displayName.substringAfterLast('.', "")
                val format = BookFormat.fromExtension(extension)
                    ?: BookFormat.fromMimeType(candidate.mimeType)
                if (format == null || !documentRepository.supportsFormat(format)) {
                    unsupported++
                    continue
                }
                if (libraryRepository.findBookByUri(candidate.uri) != null) {
                    already++
                    continue
                }

                val placeholder = Book(
                    title = fileStem(candidate.displayName),
                    uri = candidate.uri,
                    format = format,
                    sizeBytes = candidate.sizeBytes,
                )

                val metadata = documentRepository.readMetadata(placeholder).getOrNull()
                val book = placeholder.copy(
                    title = metadata?.title?.takeIf { it.isNotBlank() } ?: placeholder.title,
                    author = metadata?.author?.takeIf { it.isNotBlank() },
                    language = metadata?.language,
                    contentCount = metadata?.contentCount,
                )
                val bookId = libraryRepository.saveBooks(listOf(book)).firstOrNull() ?: book.id

                // Cover extraction is the slow part and is not needed for the book to be usable,
                // so it runs after the row exists: a failure leaves a coverless book, not a
                // missing one.
                val coverPath = documentRepository.extractCover(book.copy(id = bookId)).getOrNull()
                if (coverPath != null) {
                    libraryRepository.updateBook(book.copy(id = bookId, coverPath = coverPath))
                }
                imported++
            }

            ImportSummary(imported = imported, alreadyInLibrary = already, unsupported = unsupported)
        }
}

/** Deletes books along with their reading positions and bookmarks. */
class DeleteBooksUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ReadingProgressRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(bookIds: List<Long>) = withContext(dispatchers.io) {
        bookIds.forEach { bookId ->
            bookmarkRepository.deleteBookmarksForBook(bookId)
            progressRepository.clearPosition(bookId)
        }
        libraryRepository.deleteBooks(bookIds)
    }
}

/** Marks or unmarks a book as a favourite. */
class ToggleFavoriteUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(bookId: Long, isFavorite: Boolean) =
        libraryRepository.setFavorite(bookId, isFavorite)
}
