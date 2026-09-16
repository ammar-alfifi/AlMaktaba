package com.mylibrary.core.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A device folder the user granted MyLibrary access to.
 *
 * `uri` carries a unique index for the same reason a book's does: the tree URI *is* the folder's
 * identity, and pointing at the same directory twice must update one row rather than create a second
 * one that shows the same books under two chips.
 *
 * `lastScannedAt` is nullable and means "never enumerated", which is a real state — a folder added by
 * a build that could not read it, or one whose first scan was interrupted.
 */
@Entity(
    tableName = "folders",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["addedAt"]),
    ],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val uri: String,
    val name: String,
    val addedAt: Long,
    val lastScannedAt: Long? = null,
)
