package com.mylibrary.core.domain

import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.FolderEntry
import com.mylibrary.core.domain.engine.FolderScan
import com.mylibrary.core.domain.engine.FolderScanner
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ReadingPosition
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.FolderRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

/**
 * A folder store and a folder scanner, in memory.
 *
 * The scanner's fake is the more interesting of the two: it is where "what is in this folder" is
 * decided, so a test can say *the file was deleted between scans* or *the grant was revoked* by
 * changing one list, with no Android and no file system involved.
 */
/**
 * A folder store over the same in-memory library the books live in.
 *
 * Membership is read from the *books*, not kept in a map of its own, because that is where the real
 * database keeps it: `books.folderId` is the association, and a fake with its own copy could agree
 * with the code under test while disagreeing with the schema.
 */
class FakeFolderRepository(
    private val library: FakeLibraryRepository = FakeLibraryRepository(),
) : FolderRepository {
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    var nextId = 1L
    val assignments = mutableListOf<Pair<List<Long>, Long?>>()

    private fun idsIn(folderId: Long): List<Long> =
        library.books.value.filter { it.folderId == folderId }.map { it.id }

    override fun observeFolders(): Flow<List<FolderSummary>> =
        combine(folders, library.books) { list, books ->
            list.sortedBy { it.name }.map { folder ->
                FolderSummary(folder = folder, bookCount = books.count { it.folderId == folder.id })
            }
        }

    override fun observeFolder(folderId: Long): Flow<Folder?> =
        folders.map { list -> list.firstOrNull { it.id == folderId } }

    override suspend fun getFolder(folderId: Long): Folder? =
        folders.value.firstOrNull { it.id == folderId }

    override suspend fun findFolderByUri(uri: String): Folder? =
        folders.value.firstOrNull { it.uri == uri }

    override suspend fun saveFolder(folder: Folder): Long {
        val existing = folder.uri.let { uri -> folders.value.firstOrNull { it.uri == uri } }
        return if (existing != null) {
            existing.id
        } else {
            val id = nextId++
            folders.value = folders.value + folder.copy(id = id)
            id
        }
    }

    override suspend fun renameFolder(folderId: Long, name: String) {
        folders.value = folders.value.map { if (it.id == folderId) it.copy(name = name) else it }
    }

    override suspend fun markScanned(folderId: Long, scannedAt: Long) {
        folders.value = folders.value.map {
            if (it.id == folderId) it.copy(lastScannedAt = scannedAt) else it
        }
    }

    override suspend fun deleteFolder(folderId: Long) {
        // The real implementation clears the association and deletes the row in one transaction;
        // here the books are un-filed first, so a test can assert the reader kept their library.
        assignBooks(idsIn(folderId), folderId = null)
        folders.value = folders.value.filterNot { it.id == folderId }
    }

    override suspend fun assignBooks(bookIds: List<Long>, folderId: Long?) {
        assignments += bookIds to folderId
        library.books.value = library.books.value.map { book ->
            if (book.id in bookIds) book.copy(folderId = folderId) else book
        }
    }

    override suspend fun bookIdsInFolder(folderId: Long): List<Long> = idsIn(folderId)
}

class FakeFolderScanner(
    /** The entries each tree URI currently holds, so a test can add or remove files between scans. */
    val contents: MutableMap<String, MutableList<FolderEntry>> = mutableMapOf(),
) : FolderScanner {
    var permissionGranted = true
    val released = mutableListOf<String>()
    var scanCount = 0
    var failDirectories = 0
    var truncate = false

    override fun takePermission(treeUri: String): Boolean = permissionGranted

    override fun releasePermission(treeUri: String) {
        released += treeUri
    }

    override fun hasPermission(treeUri: String): Boolean = permissionGranted

    override fun displayName(treeUri: String): String? = treeUri.substringAfterLast('/')

    override suspend fun scan(treeUri: String): FolderScan {
        scanCount++
        return FolderScan(
            entries = contents[treeUri].orEmpty().toList(),
            truncated = truncate,
            failedDirectories = failDirectories,
        )
    }
}

/** A folder entry for a supported format, which is what a test almost always needs. */
fun folderEntry(path: String, extension: String = "epub") = FolderEntry(
    uri = "content://tree/$path",
    displayName = path.substringAfterLast('/'),
    mimeType = null,
    sizeBytes = 1024,
    relativePath = path,
)
