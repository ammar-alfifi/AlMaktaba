package com.mylibrary.core.data.repository

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.data.local.dao.BookDao
import com.mylibrary.core.data.local.toDomain
import com.mylibrary.core.data.local.toEntity
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.repository.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val dispatchers: DispatcherProvider,
) : LibraryRepository {

    override fun observeBooks(sort: LibrarySort, query: String): Flow<List<Book>> {
        val pattern = query.toLikePattern()
        val rows = when (sort) {
            LibrarySort.RECENTLY_READ -> bookDao.observeRecentlyRead(pattern)
            LibrarySort.RECENTLY_ADDED -> bookDao.observeRecentlyAdded(pattern)
            LibrarySort.TITLE_ASC -> bookDao.observeTitleAscending(pattern)
            LibrarySort.TITLE_DESC -> bookDao.observeTitleDescending(pattern)
            LibrarySort.AUTHOR -> bookDao.observeByAuthor(pattern)
        }
        // Mapping runs on the IO dispatcher so that a large library's row-to-model conversion never
        // lands on the main thread when the Flow is collected there.
        return rows.map { entities -> entities.map { it.toDomain() } }.flowOn(dispatchers.io)
    }

    override fun observeBook(bookId: Long): Flow<Book?> =
        bookDao.observeById(bookId).map { it?.toDomain() }.flowOn(dispatchers.io)

    override suspend fun getBook(bookId: Long): Book? = withContext(dispatchers.io) {
        bookDao.getById(bookId)?.toDomain()
    }

    override suspend fun findBookByUri(uri: String): Book? = withContext(dispatchers.io) {
        bookDao.getByUri(uri)?.toDomain()
    }

    /**
     * Saves books, matching on the document URI.
     *
     * An existing row is *updated* rather than replaced. Replacing would delete the row and
     * re-insert it, and the `ON DELETE CASCADE` on the reading-position and bookmark tables would
     * take the user's place in the book and every note they had written with it.
     */
    override suspend fun saveBooks(books: List<Book>): List<Long> = withContext(dispatchers.io) {
        books.map { book ->
            val existing = bookDao.getByUri(book.uri)
            if (existing == null) {
                bookDao.insert(book.copy(id = Book.NO_ID).toEntity())
            } else {
                bookDao.update(book.copy(id = existing.id).toEntity())
                existing.id
            }
        }
    }

    override suspend fun updateBook(book: Book) = withContext(dispatchers.io) {
        bookDao.update(book.toEntity())
    }

    override suspend fun deleteBooks(bookIds: List<Long>) = withContext(dispatchers.io) {
        if (bookIds.isNotEmpty()) bookDao.deleteByIds(bookIds)
    }

    override suspend fun setFavorite(bookId: Long, isFavorite: Boolean) = withContext(dispatchers.io) {
        bookDao.setFavorite(bookId, isFavorite)
    }

    override suspend fun markOpened(bookId: Long, openedAt: Long) = withContext(dispatchers.io) {
        bookDao.markOpened(bookId, openedAt)
    }

    override fun observeBookCount(): Flow<Int> = bookDao.observeCount().flowOn(dispatchers.io)

    override fun observeAvailableFormats(): Flow<Set<BookFormat>> =
        bookDao.observeFormats()
            .map { names ->
                names.mapNotNull { name -> BookFormat.entries.firstOrNull { it.name == name } }.toSet()
            }
            .flowOn(dispatchers.io)

    /**
     * Turns user input into a SQL `LIKE` pattern.
     *
     * The wildcards `%` and `_` are escaped, along with the escape character itself, so that typing
     * a `%` searches for a literal percent sign instead of matching the entire library. The escape
     * character has to be escaped first, otherwise it would double-escape the wildcards added below.
     * The DAO's queries all declare `ESCAPE '\'` to match.
     */
    private fun String.toLikePattern(): String {
        val escaped = trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
