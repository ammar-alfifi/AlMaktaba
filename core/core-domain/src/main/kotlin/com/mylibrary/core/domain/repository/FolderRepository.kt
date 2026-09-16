package com.mylibrary.core.domain.repository

import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import kotlinx.coroutines.flow.Flow

/**
 * Remembers the device folders the user has added, and which books came from them.
 *
 * A separate repository from [LibraryRepository] rather than more methods on it, because the two
 * answer different questions: that one owns *books*, this one owns the grouping of books, and the
 * grouping is a property of a folder on a device rather than of a book in a library.
 */
interface FolderRepository {

    /** Every folder, with the number of books in it, ordered by name. */
    fun observeFolders(): Flow<List<FolderSummary>>

    fun observeFolder(folderId: Long): Flow<Folder?>

    suspend fun getFolder(folderId: Long): Folder?

    /** Looks a folder up by its tree URI, so re-picking one updates rather than duplicates. */
    suspend fun findFolderByUri(uri: String): Folder?

    /** Inserts a folder, or updates the existing row with the same URI. Returns its id. */
    suspend fun saveFolder(folder: Folder): Long

    suspend fun renameFolder(folderId: Long, name: String)

    /** Records that the folder was just enumerated. */
    suspend fun markScanned(folderId: Long, scannedAt: Long = System.currentTimeMillis())

    /**
     * Removes the folder, leaving the books where they are.
     *
     * The books' association is cleared in the same transaction as the row's deletion, because a book
     * pointing at a folder that no longer exists is a book that would vanish from every filtered view
     * — including "no folder" — with nothing to explain why.
     */
    suspend fun deleteFolder(folderId: Long)

    /** Files books under a folder, or clears their folder when [folderId] is `null`. */
    suspend fun assignBooks(bookIds: List<Long>, folderId: Long?)

    /** The ids of the books filed under a folder. */
    suspend fun bookIdsInFolder(folderId: Long): List<Long>
}
