package com.mylibrary.core.domain.usecase

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.common.fileStem
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.common.naturalSortKey
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.FolderSummary
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ReadingPosition
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.DocumentRepository
import com.mylibrary.core.domain.repository.FolderRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
    private val dispatchers: DispatcherProvider,
) {
    operator fun invoke(
        sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
        query: String = "",
        favoritesOnly: Boolean = false,
        formats: Set<BookFormat> = emptySet(),
        folderId: Long? = null,
    ): Flow<List<LibraryItem>> =
        combine(
            libraryRepository.observeBooks(sort, query),
            progressRepository.observeAllPositions(),
        ) { books, positions ->
            val progressByBook = positions.associateBy { it.bookId }
            books.asSequence()
                .filter { !favoritesOnly || it.isFavorite }
                .filter { formats.isEmpty() || it.format in formats }
                // Filtered here rather than in SQL, exactly like the two filters above: a change to
                // either stream re-emits immediately, and the query above already carries the column.
                .filter { folderId == null || it.folderId == folderId }
                .map { book -> LibraryItem(book, progressByBook[book.id]) }
                .toList()
        }
            // The join runs off the main thread, for the same reason the repository's row-to-model
            // mapping does: it walks the whole library on every emission, and every emission is
            // triggered by something the user just did — a filter, a folder chip, a progress save.
            .flowOn(dispatchers.io)

    /**
     * The book to offer next: the most recently read one in scope that the reader has not finished.
     *
     * [folderId] is what makes the offer follow the shelf the reader is looking at. A device folder
     * is a series, so with one open the book to continue is that series' own — the most recent book
     * in the library as a whole is the wrong answer to "carry on" once the reader has narrowed the
     * shelf to one folder.
     *
     * A book that has never been *opened* is never offered: "continue" on a book nobody has started
     * is a lie, and the shelf itself is where an unread book is found. Everything finished, or
     * nothing started at all, therefore means `null` — no button — rather than a button pointing at
     * a book the reader has no position in. That is also what stops the button from reopening the
     * volume the reader has just moved on from: the next book was opened more recently than it.
     */
    fun continueReading(folderId: Long? = null): Flow<LibraryItem?> =
        invoke(sort = LibrarySort.RECENTLY_READ, folderId = folderId).map { items ->
            items.firstOrNull { it.position != null && !it.isFinished }
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
    /** The folder this file came from, when it was found by scanning one rather than picked. */
    val folderId: Long? = null,
)

/** What an import actually did, so the UI can report it honestly. */
data class ImportSummary(
    val imported: Int,
    val alreadyInLibrary: Int,
    val unsupported: Int,
) {
    val total: Int get() = imported + alreadyInLibrary + unsupported
}

/** The only URI scheme the library ever stores; see [ImportBooksUseCase]. */
private const val CONTENT_URI_SCHEME = "content://"

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
                // MyLibrary reads exclusively through the Storage Access Framework, so a book is
                // only ever a `content://` document the app holds a grant for. Anything else — a
                // `file://` path a legacy or malformed VIEW intent carried in, say — cannot be
                // opened by the reader at all, so importing it would file a book that is guaranteed
                // to fail the moment it is tapped. It is counted as unsupported, exactly like a
                // format no engine reads, rather than silently recorded.
                if (!candidate.uri.startsWith(CONTENT_URI_SCHEME)) {
                    unsupported++
                    continue
                }
                val extension = candidate.displayName.substringAfterLast('.', "")
                val format = BookFormat.fromExtension(extension)
                    ?: BookFormat.fromMimeType(candidate.mimeType)
                if (format == null || !documentRepository.supportsFormat(format)) {
                    unsupported++
                    continue
                }
                val existing = libraryRepository.findBookByUri(candidate.uri)
                if (existing != null) {
                    // A book the reader already had, now found inside a folder they just added: it is
                    // filed under that folder rather than left out of it. Without this, importing a
                    // series the reader had partly collected by hand leaves the shelf visibly missing
                    // the volumes they already owned.
                    if (candidate.folderId != null && existing.folderId == null) {
                        libraryRepository.updateBook(existing.copy(folderId = candidate.folderId))
                    }
                    already++
                    continue
                }

                val placeholder = Book(
                    title = fileStem(candidate.displayName),
                    uri = candidate.uri,
                    format = format,
                    sizeBytes = candidate.sizeBytes,
                    folderId = candidate.folderId,
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

/**
 * The books either side of an open one, in its folder's own order.
 *
 * This is the reader's *sequence*: the volume that was finished, the one being read, and the one
 * after it. Both neighbours travel together rather than only the next one, because the reader draws
 * the book on either side of the open one — a reader who scrolls back up through the seam has to
 * find the previous volume there, not a wall.
 *
 * [previous] and [next] are `null` at the ends of a folder, for a book filed on its own, and for a
 * book whose folder is no longer in the library; the reader then ends where it always did.
 *
 * [folderName] travels with them so the reader can say *which* series is being continued: a seam
 * offering "Vol 3" is a guess about what the reader owns, while "Next in «Detective Conan»" is a
 * fact they can check at a glance. [book] itself comes back so the sequence is one answer rather
 * than three queries whose results the caller has to know how to line up.
 */
data class FolderSequence(
    val book: Book,
    val previous: Book?,
    val next: Book?,
    val folderName: String?,
)

/**
 * The books either side of [bookId] in its device folder.
 *
 * A folder is a series, and a reader who has just finished volume two overwhelmingly wants volume
 * three — so the reader carries them into it instead of sending them back to the shelf to find it.
 *
 * **The order is the folder's own, not the shelf's.** Siblings are sorted by [naturalSortKey] of the
 * title, which is the same natural order the folder was *scanned* in, so `Vol 2` precedes `Vol 10`
 * rather than following it — a plain string sort would put the tenth volume second. Deliberately not
 * the library's display sort: "recently read" is a property of the reader's history and not of the
 * series, and a next volume that depended on which books were opened most recently would move around
 * underneath the reader between one tap and the next.
 *
 * Observed rather than resolved once, because the library can change while a book is open — a volume
 * imported, a book moved to another folder — and the reader has to carry on into what is true when
 * they arrive at the seam rather than into what was true when they opened the book.
 */
class ObserveFolderSequenceUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val folderRepository: FolderRepository,
) {
    operator fun invoke(bookId: Long): Flow<FolderSequence?> = combine(
        libraryRepository.observeBook(bookId),
        libraryRepository.observeBooks(),
        folderRepository.observeFolders(),
    ) { current, books, folders -> sequenceOf(current, books, folders) }

    private fun sequenceOf(
        current: Book?,
        books: List<Book>,
        folders: List<FolderSummary>,
    ): FolderSequence? {
        val folderId = current?.folderId ?: return null

        val siblings = folderOrder(books, folderId)
        // A book that is in no list of its own folder — deleted, unfiled, or filtered out between
        // the two queries — has no sequence, and the reader ends where it always did.
        val index = siblings.indexOfFirst { it.id == current.id }
        if (index < 0) return null

        return FolderSequence(
            book = current,
            previous = siblings.getOrNull(index - 1),
            next = siblings.getOrNull(index + 1),
            folderName = folders.folderNameOf(folderId),
        )
    }
}

/**
 * A folder's books in the order the folder is a series in.
 *
 * The key is computed once per book and then used as the sort key, rather than being a comparator
 * selector: a selector is re-evaluated on every comparison, which turns a sort of a series into
 * `n log n` string normalisations of the same `n` titles.
 */
private fun folderOrder(books: List<Book>, folderId: Long): List<Book> = books
    .asSequence()
    .filter { it.folderId == folderId }
    // The id breaks a tie between two volumes whose titles sort identically, so the order is
    // stable rather than dependent on the order the rows came back in.
    .map { book -> naturalSortKey(book.title) to book }
    .sortedWith(compareBy({ it.first }, { it.second.id }))
    .map { it.second }
    .toList()

/** The name of a folder, or `null` when the row is gone — a book keeps working without its folder. */
private fun List<FolderSummary>.folderNameOf(folderId: Long): String? =
    firstOrNull { it.folder.id == folderId }?.folder?.name
