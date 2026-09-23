package com.mylibrary.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One unit of a book's text, indexed so the whole library can be searched without opening a single
 * book at search time.
 *
 * A unit is whatever the document naturally divides into — a chapter for an EPUB or a TXT file, a
 * page's text layer for a PDF — and the row carries the locator of that unit's start so a hit can be
 * opened at the right place. [text] is the unit's plain text, which is what `LIKE` matches against;
 * `label` is the human-readable name ("Page 12", a chapter title) shown beside a hit.
 *
 * The foreign key cascades, so deleting a book deletes its index with it. That is safe in a way a
 * foreign key on `books` would not be: this table is a *child*, so the cascade runs when a book row
 * is deleted and never touches the book itself.
 */
@Entity(
    tableName = "search_index",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class SearchIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val label: String?,
    val locator: String,
    val text: String,
)

/**
 * A book whose text has been indexed, and when.
 *
 * Separate from [SearchIndexEntity] because "indexed" and "has text" are different facts: a comic
 * indexes to no chunks at all, and without this row it would be re-opened and re-scanned on every
 * pass forever. A row here means "we have looked", whether or not the look found anything.
 */
@Entity(
    tableName = "indexed_books",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IndexedBookEntity(
    @PrimaryKey val bookId: Long,
    val indexedAt: Long,
)
