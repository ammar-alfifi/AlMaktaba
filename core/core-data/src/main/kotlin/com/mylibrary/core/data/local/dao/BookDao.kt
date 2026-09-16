package com.mylibrary.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mylibrary.core.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for the library.
 *
 * There is one query per sort order rather than one query with a dynamic `ORDER BY`. SQLite cannot
 * parameterise an `ORDER BY`, so the alternative is string-concatenating SQL, which is both an
 * injection risk and impossible for the compiler to verify. Five small verified queries are the
 * cheaper trade.
 *
 * All of them take the same search [pattern] (already wrapped in `%` and escaped by the repository)
 * so that filtering and ordering compose without a second query path. `IFNULL(author, '')` is what
 * lets a book with no author still match: `NULL LIKE '%x%'` is `NULL`, not false, and would
 * silently drop those rows.
 *
 * Sorting uses SQLite's `NOCASE` collation, which folds ASCII case only. For Arabic titles this is
 * harmless — the Arabic block is laid out in alphabetical order in Unicode, so a binary comparison
 * already yields the expected sequence.
 */
@Dao
interface BookDao {

    @Query(
        """
        SELECT * FROM books
        WHERE title LIKE :pattern ESCAPE '\' OR IFNULL(author, '') LIKE :pattern ESCAPE '\'
        ORDER BY CASE WHEN lastOpenedAt IS NULL THEN 1 ELSE 0 END, lastOpenedAt DESC, addedAt DESC
        """,
    )
    fun observeRecentlyRead(pattern: String): Flow<List<BookEntity>>

    @Query(
        """
        SELECT * FROM books
        WHERE title LIKE :pattern ESCAPE '\' OR IFNULL(author, '') LIKE :pattern ESCAPE '\'
        ORDER BY addedAt DESC
        """,
    )
    fun observeRecentlyAdded(pattern: String): Flow<List<BookEntity>>

    @Query(
        """
        SELECT * FROM books
        WHERE title LIKE :pattern ESCAPE '\' OR IFNULL(author, '') LIKE :pattern ESCAPE '\'
        ORDER BY title COLLATE NOCASE ASC
        """,
    )
    fun observeTitleAscending(pattern: String): Flow<List<BookEntity>>

    @Query(
        """
        SELECT * FROM books
        WHERE title LIKE :pattern ESCAPE '\' OR IFNULL(author, '') LIKE :pattern ESCAPE '\'
        ORDER BY title COLLATE NOCASE DESC
        """,
    )
    fun observeTitleDescending(pattern: String): Flow<List<BookEntity>>

    /** Author first, falling back to the title so authorless books stay in a sensible place. */
    @Query(
        """
        SELECT * FROM books
        WHERE title LIKE :pattern ESCAPE '\' OR IFNULL(author, '') LIKE :pattern ESCAPE '\'
        ORDER BY IFNULL(author, title) COLLATE NOCASE ASC
        """,
    )
    fun observeByAuthor(pattern: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeById(bookId: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getById(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): BookEntity?

    @Query("SELECT COUNT(*) FROM books")
    fun observeCount(): Flow<Int>

    @Query("SELECT DISTINCT format FROM books")
    fun observeFormats(): Flow<List<String>>

    /**
     * Inserts a book, or updates the existing row when the URI already exists.
     *
     * `IGNORE` would keep the old row (losing a changed title) and `REPLACE` would delete-then-insert,
     * which cascades away the book's reading position and bookmarks. Neither is what re-importing a
     * book should do, so the repository resolves an existing row and updates it instead.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: Long, isFavorite: Boolean)

    @Query("UPDATE books SET lastOpenedAt = :openedAt WHERE id = :bookId")
    suspend fun markOpened(bookId: Long, openedAt: Long)

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteByIds(bookIds: List<Long>)

    /** Files books under a folder, or clears their folder when [folderId] is `null`. */
    @Query("UPDATE books SET folderId = :folderId WHERE id IN (:bookIds)")
    suspend fun assignFolder(bookIds: List<Long>, folderId: Long?)

    /** Un-files every book in a folder, used when the folder itself is removed. */
    @Query("UPDATE books SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("SELECT id FROM books WHERE folderId = :folderId")
    suspend fun idsInFolder(folderId: Long): List<Long>

    /**
     * The folder of every filed book, for counting how many each holds.
     *
     * A column of ids rather than `COUNT(*) GROUP BY`, because the count has to stay live as books
     * are imported, deleted or moved: a `Flow` over the column re-emits on any of those, and the
     * tally is a `groupingBy` in the repository rather than a second query to keep in step.
     */
    @Query("SELECT folderId FROM books WHERE folderId IS NOT NULL")
    fun observeAllFolderIds(): Flow<List<Long>>
}
