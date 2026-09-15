package com.mylibrary.core.domain.repository

import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.LibrarySort
import kotlinx.coroutines.flow.Flow

/** Reads and writes the user's library. */
interface LibraryRepository {

    /**
     * The library, ordered by [sort], optionally filtered by a free-text [query] matched against
     * title and author.
     *
     * A `Flow` rather than a suspend function so that importing a book or finishing one updates
     * every screen showing the library without any manual refresh.
     */
    fun observeBooks(sort: LibrarySort = LibrarySort.RECENTLY_ADDED, query: String = ""): Flow<List<Book>>

    /** One book, or `null` if it was deleted. Emits on every change to that book. */
    fun observeBook(bookId: Long): Flow<Book?>

    suspend fun getBook(bookId: Long): Book?

    /** Looks a book up by its document URI, so a re-import updates rather than duplicates. */
    suspend fun findBookByUri(uri: String): Book?

    /** Inserts new books and updates existing ones (matched by [Book.uri]). Returns their ids. */
    suspend fun saveBooks(books: List<Book>): List<Long>

    suspend fun updateBook(book: Book)

    suspend fun deleteBooks(bookIds: List<Long>)

    suspend fun setFavorite(bookId: Long, isFavorite: Boolean)

    /** Records that the book was just opened, which drives the "recently read" sort. */
    suspend fun markOpened(bookId: Long, openedAt: Long = System.currentTimeMillis())

    /** Total number of books, used by the empty state and the settings screen. */
    fun observeBookCount(): Flow<Int>

    /** Distinct formats present in the library, for the filter chips. */
    fun observeAvailableFormats(): Flow<Set<BookFormat>>
}
