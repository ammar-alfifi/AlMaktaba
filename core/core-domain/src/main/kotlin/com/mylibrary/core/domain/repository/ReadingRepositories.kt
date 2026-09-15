package com.mylibrary.core.domain.repository

import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.ReadingPosition
import kotlinx.coroutines.flow.Flow

/** Where the user is in each book. */
interface ReadingProgressRepository {

    fun observePosition(bookId: Long): Flow<ReadingPosition?>

    /** Progress for every book, so the library can draw a progress bar on each card. */
    fun observeAllPositions(): Flow<List<ReadingPosition>>

    suspend fun savePosition(position: ReadingPosition)

    suspend fun clearPosition(bookId: Long)

    /**
     * Positions not touched since [olderThan], so a maintenance task can prune very old rows.
     */
    suspend fun deletePositionsOlderThan(olderThan: Long)
}

/** Bookmarks and highlights. */
interface BookmarkRepository {

    fun observeBookmarks(bookId: Long): Flow<List<Bookmark>>

    /** Every bookmark in the library, newest first, for the "all bookmarks" screen. */
    fun observeAllBookmarks(): Flow<List<Bookmark>>

    suspend fun addBookmark(bookmark: Bookmark): Long

    suspend fun updateBookmark(bookmark: Bookmark)

    suspend fun deleteBookmark(bookmarkId: Long)

    suspend fun deleteBookmarksForBook(bookId: Long)
}
