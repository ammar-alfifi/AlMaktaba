package com.mylibrary.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mylibrary.core.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for the device folders the library is grouped by.
 *
 * Ordered by name with `NOCASE`, which folds ASCII case only — harmless for Arabic names, whose
 * block is already in alphabetical order in Unicode, and right for the Latin ones this app also
 * shows.
 */
@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :folderId")
    fun observeById(folderId: Long): Flow<FolderEntity?>

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getById(folderId: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): FolderEntity?

    /**
     * Inserts a folder.
     *
     * `ABORT` rather than `REPLACE`, for the reason `BookDao.insert` gives: `REPLACE` is a
     * delete-then-insert, and this table is the one a book points at.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :folderId")
    suspend fun rename(folderId: Long, name: String)

    @Query("UPDATE folders SET lastScannedAt = :scannedAt WHERE id = :folderId")
    suspend fun markScanned(folderId: Long, scannedAt: Long)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteById(folderId: Long)
}
