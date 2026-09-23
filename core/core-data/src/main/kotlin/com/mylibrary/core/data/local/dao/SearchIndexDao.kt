package com.mylibrary.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mylibrary.core.data.local.entity.IndexedBookEntity
import com.mylibrary.core.data.local.entity.SearchIndexEntity

/**
 * Reads and writes for the full-text index behind "search inside books".
 *
 * Matching is `LIKE '%query%'` rather than FTS. The index is a personal library's worth of plain
 * text and SQLite scans it quickly; `LIKE` is also what the title search already uses, so the two
 * searches fold case the same way (ASCII only, which is harmless for Arabic — the Arabic block has
 * no case and is laid out in alphabetical order in Unicode). FTS would be the choice for a corpus
 * orders of magnitude larger; here it would add a tokenizer, a second query dialect and a migration
 * for no answer a reader could tell apart.
 */
@Dao
interface SearchIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<SearchIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markIndexed(marker: IndexedBookEntity)

    @Query("DELETE FROM search_index WHERE bookId = :bookId")
    suspend fun deleteChunks(bookId: Long)

    @Query("DELETE FROM indexed_books WHERE bookId = :bookId")
    suspend fun unmarkIndexed(bookId: Long)

    @Query("SELECT bookId FROM indexed_books")
    suspend fun indexedBookIds(): List<Long>

    /**
     * Every chunk containing [pattern].
     *
     * [pattern] is already wrapped in `%` and escaped by the repository. The `LIMIT` is a memory
     * guard, not a result cap: a one-letter query can match most of the library, and the rows carry
     * their full text. The repository groups and trims to a per-book limit afterwards.
     */
    @Query("SELECT * FROM search_index WHERE text LIKE :pattern ESCAPE '\\' LIMIT :limit")
    suspend fun searchChunks(pattern: String, limit: Int): List<SearchIndexEntity>
}
