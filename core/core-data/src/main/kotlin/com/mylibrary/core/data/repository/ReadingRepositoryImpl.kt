package com.mylibrary.core.data.repository

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.data.local.dao.BookmarkDao
import com.mylibrary.core.data.local.dao.ReadingPositionDao
import com.mylibrary.core.data.local.toDomain
import com.mylibrary.core.data.local.toEntity
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.ReadingPosition
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.ReadingProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ReadingProgressRepositoryImpl @Inject constructor(
    private val dao: ReadingPositionDao,
    private val dispatchers: DispatcherProvider,
) : ReadingProgressRepository {

    override fun observePosition(bookId: Long): Flow<ReadingPosition?> =
        dao.observeByBook(bookId).map { it?.toDomain() }.flowOn(dispatchers.io)

    override fun observeAllPositions(): Flow<List<ReadingPosition>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(dispatchers.io)

    override suspend fun savePosition(position: ReadingPosition) = withContext(dispatchers.io) {
        dao.upsert(position.toEntity())
    }

    override suspend fun clearPosition(bookId: Long) = withContext(dispatchers.io) {
        dao.deleteByBook(bookId)
    }

    override suspend fun deletePositionsOlderThan(olderThan: Long) = withContext(dispatchers.io) {
        dao.deleteOlderThan(olderThan)
    }
}

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao,
    private val dispatchers: DispatcherProvider,
) : BookmarkRepository {

    override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> =
        dao.observeByBook(bookId).map { rows -> rows.map { it.toDomain() } }.flowOn(dispatchers.io)

    override fun observeAllBookmarks(): Flow<List<Bookmark>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(dispatchers.io)

    override suspend fun addBookmark(bookmark: Bookmark): Long = withContext(dispatchers.io) {
        dao.insert(bookmark.copy(id = Bookmark.NO_ID).toEntity())
    }

    override suspend fun updateBookmark(bookmark: Bookmark) = withContext(dispatchers.io) {
        dao.update(bookmark.toEntity())
    }

    override suspend fun deleteBookmark(bookmarkId: Long) = withContext(dispatchers.io) {
        dao.deleteById(bookmarkId)
    }

    override suspend fun deleteBookmarksForBook(bookId: Long) = withContext(dispatchers.io) {
        dao.deleteByBook(bookId)
    }
}
