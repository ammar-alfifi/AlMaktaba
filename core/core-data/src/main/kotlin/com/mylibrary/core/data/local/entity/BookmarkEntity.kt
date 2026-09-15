package com.mylibrary.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A bookmark or highlight.
 *
 * A highlight is a bookmark with a [colorArgb]; there is no separate table because there is no
 * separate behaviour. Splitting them would double the DAO surface and force the bookmarks list to
 * merge two streams that already arrive in the same order.
 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"]), Index(value = ["createdAt"])],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val bookId: Long,

    val locatorType: String,
    val locatorIndex: Int,
    val locatorOffset: Int = 0,

    val label: String? = null,
    val excerpt: String? = null,
    val note: String? = null,
    val colorArgb: Int? = null,
    val createdAt: Long,
)
