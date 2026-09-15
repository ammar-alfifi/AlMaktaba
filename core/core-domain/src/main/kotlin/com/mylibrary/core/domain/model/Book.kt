package com.mylibrary.core.domain.model

/**
 * A book in the user's library.
 *
 * [id] is assigned by the database; a new book is imported with [NO_ID]. [uri] is the persistent
 * `content://` URI string handed out by the Storage Access Framework — MyLibrary never copies the
 * book's bytes into its own storage, it holds a revocable grant and reads through it.
 */
data class Book(
    val id: Long = NO_ID,
    val title: String,
    val author: String? = null,
    val uri: String,
    val format: BookFormat,
    val sizeBytes: Long = 0L,
    /** Path to a cover image in the app's cache, extracted on first open. `null` until then. */
    val coverPath: String? = null,
    /** Number of pages (paged formats) or chapters (reflowable formats). `null` until opened. */
    val contentCount: Int? = null,
    val language: String? = null,
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long? = null,
) {
    /** The reading progress in the range 0..1, or `null` if this book has never been opened. */
    val hasBeenOpened: Boolean get() = lastOpenedAt != null

    companion object {
        const val NO_ID: Long = 0L
    }
}
