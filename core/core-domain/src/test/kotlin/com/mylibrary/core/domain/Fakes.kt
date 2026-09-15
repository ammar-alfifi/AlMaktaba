package com.mylibrary.core.domain

import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ReadingPosition
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Hand-written test doubles for the repository contracts.
 *
 * Deliberately not a mocking framework: the fakes make the *behaviour* under test explicit — a test
 * that asserts an import skips an already-present book has to set up that book in a real list — and
 * they can't silently pass because a mock returned a default. `:core:core-domain` is a plain JVM
 * module, so these run in milliseconds with no device.
 */

class FakeLibraryRepository : LibraryRepository {
    val books = MutableStateFlow<List<Book>>(emptyList())
    var nextId = 1L
    val updates = mutableListOf<Book>()
    val deleted = mutableListOf<Long>()

    override fun observeBooks(sort: LibrarySort, query: String): Flow<List<Book>> =
        books.map { list ->
            val filtered = if (query.isBlank()) {
                list
            } else {
                list.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.author?.contains(query, ignoreCase = true) == true
                }
            }
            when (sort) {
                LibrarySort.TITLE_ASC -> filtered.sortedBy { it.title }
                LibrarySort.TITLE_DESC -> filtered.sortedByDescending { it.title }
                LibrarySort.AUTHOR -> filtered.sortedBy { it.author ?: it.title }
                LibrarySort.RECENTLY_ADDED -> filtered.sortedByDescending { it.addedAt }
                LibrarySort.RECENTLY_READ -> filtered.sortedByDescending { it.lastOpenedAt ?: 0L }
            }
        }

    override fun observeBook(bookId: Long): Flow<Book?> =
        books.map { list -> list.firstOrNull { it.id == bookId } }

    override suspend fun getBook(bookId: Long): Book? = books.value.firstOrNull { it.id == bookId }

    override suspend fun findBookByUri(uri: String): Book? = books.value.firstOrNull { it.uri == uri }

    override suspend fun saveBooks(books: List<Book>): List<Long> {
        val ids = books.map { book ->
            val existing = this.books.value.firstOrNull { it.uri == book.uri }
            val id = existing?.id ?: nextId++
            val stored = book.copy(id = id)
            this.books.value = this.books.value.filterNot { it.id == id } + stored
            id
        }
        return ids
    }

    override suspend fun updateBook(book: Book) {
        updates += book
        books.value = books.value.map { if (it.id == book.id) book else it }
    }

    override suspend fun deleteBooks(bookIds: List<Long>) {
        deleted += bookIds
        books.value = books.value.filterNot { it.id in bookIds }
    }

    override suspend fun setFavorite(bookId: Long, isFavorite: Boolean) {
        books.value = books.value.map { if (it.id == bookId) it.copy(isFavorite = isFavorite) else it }
    }

    override suspend fun markOpened(bookId: Long, openedAt: Long) {
        books.value = books.value.map { if (it.id == bookId) it.copy(lastOpenedAt = openedAt) else it }
    }

    override fun observeBookCount(): Flow<Int> = books.map { it.size }

    override fun observeAvailableFormats(): Flow<Set<BookFormat>> =
        books.map { list -> list.map { it.format }.toSet() }
}

class FakeProgressRepository : ReadingProgressRepository {
    val positions = MutableStateFlow<Map<Long, ReadingPosition>>(emptyMap())
    var lastSaved: ReadingPosition? = null

    override fun observePosition(bookId: Long): Flow<ReadingPosition?> =
        positions.map { it[bookId] }

    override fun observeAllPositions(): Flow<List<ReadingPosition>> =
        positions.map { it.values.toList() }

    override suspend fun savePosition(position: ReadingPosition) {
        lastSaved = position
        positions.value = positions.value + (position.bookId to position)
    }

    override suspend fun clearPosition(bookId: Long) {
        positions.value = positions.value - bookId
    }

    override suspend fun deletePositionsOlderThan(olderThan: Long) {
        positions.value = positions.value.filterValues { it.updatedAt >= olderThan }
    }
}

class FakeBookmarkRepository : BookmarkRepository {
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    var nextId = 1L
    val deleted = mutableListOf<Long>()

    override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> =
        bookmarks.map { list -> list.filter { it.bookId == bookId } }

    override fun observeAllBookmarks(): Flow<List<Bookmark>> = bookmarks

    override suspend fun addBookmark(bookmark: Bookmark): Long {
        val id = nextId++
        bookmarks.value = bookmarks.value + bookmark.copy(id = id)
        return id
    }

    override suspend fun updateBookmark(bookmark: Bookmark) {
        bookmarks.value = bookmarks.value.map { if (it.id == bookmark.id) bookmark else it }
    }

    override suspend fun deleteBookmark(bookmarkId: Long) {
        deleted += bookmarkId
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmarkId }
    }

    override suspend fun deleteBookmarksForBook(bookId: Long) {
        bookmarks.value = bookmarks.value.filterNot { it.bookId == bookId }
    }
}

/**
 * A `DocumentRepository` that never opens anything.
 *
 * Import tests use it to assert the "metadata could not be read" path, which must still import the
 * book — a decoder failing on a file must not cost the user the file.
 */
class FakeDocumentRepository(
    private val supportedFormats: Set<BookFormat> = BookFormat.entries.toSet(),
    private val metadata: com.mylibrary.core.domain.repository.BookMetadataResult? = null,
    private val coverPath: String? = null,
) : com.mylibrary.core.domain.repository.DocumentRepository {

    override suspend fun open(
        book: Book,
        password: String?,
    ): AppResult<com.mylibrary.core.domain.engine.OpenDocument> =
        AppResult.Failure(com.mylibrary.core.common.AppError.UnsupportedFormat(null))

    override suspend fun readMetadata(
        book: Book,
    ): AppResult<com.mylibrary.core.domain.repository.BookMetadataResult> =
        metadata
            ?.let { AppResult.Success(it) }
            ?: AppResult.Failure(com.mylibrary.core.common.AppError.CorruptDocument())

    override suspend fun extractCover(book: Book): AppResult<String?> = AppResult.Success(coverPath)

    override fun supportsFormat(format: BookFormat): Boolean = format in supportedFormats
}
