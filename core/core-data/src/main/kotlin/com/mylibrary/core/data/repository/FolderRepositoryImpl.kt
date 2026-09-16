package com.mylibrary.core.data.repository

import androidx.room.withTransaction
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.data.local.MyLibraryDatabase
import com.mylibrary.core.data.local.dao.BookDao
import com.mylibrary.core.data.local.dao.FolderDao
import com.mylibrary.core.data.local.toDomain
import com.mylibrary.core.data.local.toEntity
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import com.mylibrary.core.domain.repository.FolderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The folder store, over Room.
 *
 * Two things here are worth the code they cost. **The URI is the identity**, so saving a folder the
 * user has picked before updates the row rather than creating a second one — the same rule the book
 * repository applies, for the same reason. And **deleting a folder clears its books' folder inside
 * the same transaction**, because the alternative is a row of books pointing at a folder that no
 * longer exists: they would disappear from "all books" no filter can reach and from "no folder"
 * alike, which is a small data-loss bug that looks like books vanishing at random.
 */
@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val database: MyLibraryDatabase,
    private val folderDao: FolderDao,
    private val bookDao: BookDao,
    private val dispatchers: DispatcherProvider,
) : FolderRepository {

    override fun observeFolders(): Flow<List<FolderSummary>> =
        combine(folderDao.observeAll(), bookDao.observeAllFolderIds()) { folders, bookFolderIds ->
            val counts = bookFolderIds.groupingBy { it }.eachCount()
            folders.map { entity ->
                FolderSummary(
                    folder = entity.toDomain(),
                    bookCount = counts[entity.id] ?: 0,
                )
            }
        }.flowOn(dispatchers.io)

    override fun observeFolder(folderId: Long): Flow<Folder?> =
        folderDao.observeById(folderId).map { it?.toDomain() }.flowOn(dispatchers.io)

    override suspend fun getFolder(folderId: Long): Folder? = withContext(dispatchers.io) {
        folderDao.getById(folderId)?.toDomain()
    }

    override suspend fun findFolderByUri(uri: String): Folder? = withContext(dispatchers.io) {
        folderDao.getByUri(uri)?.toDomain()
    }

    override suspend fun saveFolder(folder: Folder): Long = withContext(dispatchers.io) {
        val existing = folderDao.getByUri(folder.uri)
        if (existing == null) {
            folderDao.insert(folder.toEntity())
        } else {
            // The name is deliberately *not* overwritten from the incoming folder: the user may have
            // renamed it, and a re-import of the same directory should not undo that.
            folderDao.update(
                existing.copy(
                    lastScannedAt = folder.lastScannedAt ?: existing.lastScannedAt,
                ),
            )
            existing.id
        }
    }

    override suspend fun renameFolder(folderId: Long, name: String) = withContext(dispatchers.io) {
        folderDao.rename(folderId, name)
    }

    override suspend fun markScanned(folderId: Long, scannedAt: Long) = withContext(dispatchers.io) {
        folderDao.markScanned(folderId, scannedAt)
    }

    override suspend fun deleteFolder(folderId: Long) = withContext(dispatchers.io) {
        database.withTransaction {
            bookDao.clearFolder(folderId)
            folderDao.deleteById(folderId)
        }
        Unit
    }

    override suspend fun assignBooks(bookIds: List<Long>, folderId: Long?) =
        withContext(dispatchers.io) {
            // An empty list would otherwise be a no-op write, and an `IN ()` list is not valid SQL.
            if (bookIds.isEmpty()) return@withContext
            bookDao.assignFolder(bookIds, folderId)
        }

    override suspend fun bookIdsInFolder(folderId: Long): List<Long> = withContext(dispatchers.io) {
        bookDao.idsInFolder(folderId)
    }
}
