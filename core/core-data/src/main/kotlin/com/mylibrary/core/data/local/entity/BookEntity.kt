package com.mylibrary.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A book row.
 *
 * The URI carries a unique index because the document URI is the book's real identity: importing
 * the same file twice must update one row rather than create two, and that rule belongs in the
 * schema where it cannot be bypassed by a caller that forgets to check first.
 *
 * `format` is stored as the enum's `name`. Storing the ordinal would silently corrupt the library
 * if [com.mylibrary.core.domain.model.BookFormat] ever gained an entry in the middle, which is
 * exactly the kind of change a new format is.
 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["addedAt"]),
        Index(value = ["lastOpenedAt"]),
        Index(value = ["title"]),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,
    val author: String?,
    val uri: String,
    val format: String,
    val sizeBytes: Long = 0,
    val coverPath: String? = null,
    val contentCount: Int? = null,
    val language: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)
