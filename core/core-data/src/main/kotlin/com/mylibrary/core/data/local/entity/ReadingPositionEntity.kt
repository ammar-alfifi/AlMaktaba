package com.mylibrary.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where the reader is in a book.
 *
 * The reading locator is flattened into columns rather than stored as a serialized blob so that
 * Room can index and query it, and — more importantly — so that a schema change to
 * `ReadingLocator` is a visible migration rather than silently unparseable JSON.
 *
 * `bookId` is the primary key: a book has exactly one reading position, and making that a schema
 * invariant means no query can ever return two rows for one book and leave the caller guessing
 * which is current.
 */
@Entity(
    tableName = "reading_positions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["updatedAt"])],
)
data class ReadingPositionEntity(
    @PrimaryKey
    val bookId: Long,

    /** `PAGED` or `REFLOWABLE`; see the mapper for why this is a string. */
    val locatorType: String,

    /** Page index for `PAGED`, chapter index for `REFLOWABLE`. */
    val locatorIndex: Int,

    /** Character offset within the chapter; always 0 for `PAGED`. */
    val locatorOffset: Int = 0,

    val percent: Float,
    val updatedAt: Long,
    val excerpt: String? = null,
)
