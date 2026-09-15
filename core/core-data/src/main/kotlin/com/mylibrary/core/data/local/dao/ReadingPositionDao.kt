package com.mylibrary.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mylibrary.core.data.local.entity.ReadingPositionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reading positions.
 *
 * [upsert] is an `@Insert` with `REPLACE` because `bookId` is the primary key: saving a position is
 * always "this book is now here", never "add another position". That makes the write a single
 * statement, which matters because the reader calls it on every page turn.
 */
@Dao
interface ReadingPositionDao {

    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId")
    fun observeByBook(bookId: Long): Flow<ReadingPositionEntity?>

    @Query("SELECT * FROM reading_positions")
    fun observeAll(): Flow<List<ReadingPositionEntity>>

    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId")
    suspend fun getByBook(bookId: Long): ReadingPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("DELETE FROM reading_positions WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("DELETE FROM reading_positions WHERE updatedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
