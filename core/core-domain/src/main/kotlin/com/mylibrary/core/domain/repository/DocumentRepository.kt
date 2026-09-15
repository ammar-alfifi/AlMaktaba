package com.mylibrary.core.domain.repository

import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.model.Book

/**
 * Opens books for reading and reading-adjacent work such as metadata extraction.
 *
 * This is the only door between the app and the decoder modules: `:core:core-data` implements it by
 * picking the right `DocumentEngine` for the book's format, and everything above the data layer
 * sees only [OpenDocument].
 */
interface DocumentRepository {

    /**
     * Opens [book], prompting for a password in the UI if the result is
     * [com.mylibrary.core.common.AppError.PasswordRequired].
     */
    suspend fun open(book: Book, password: String? = null): AppResult<OpenDocument>

    /**
     * Reads a book's metadata without leaving it open, used when importing so the library shows a
     * real title and author instead of a file name.
     */
    suspend fun readMetadata(book: Book): AppResult<BookMetadataResult>

    /**
     * Extracts a cover into the app's cache directory and returns its absolute path.
     *
     * The cover is written to disk rather than kept in memory because the library grid shows
     * hundreds of them at once and Coil can then cache and downsample them like any other image.
     */
    suspend fun extractCover(book: Book): AppResult<String?>

    /** True when the running build can decode [format] — i.e. an engine is registered for it. */
    fun supportsFormat(format: com.mylibrary.core.domain.model.BookFormat): Boolean
}

/** Metadata read out of a document, ready to be merged into a [Book]. */
data class BookMetadataResult(
    val title: String?,
    val author: String?,
    val language: String?,
    val contentCount: Int?,
)
