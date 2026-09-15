package com.mylibrary.core.common

/**
 * Every failure MyLibrary models explicitly.
 *
 * The set is closed on purpose: a feature module asks "which of these happened?" and maps it to a
 * localized string, instead of string-matching on exception messages from a decoder library.
 */
sealed interface AppError {

    /** The file could not be read: it is gone, unreadable, or the URI grant was revoked. */
    data class FileAccess(val reason: String? = null) : AppError

    /** The file is not a valid document of its claimed format. */
    data class CorruptDocument(val reason: String? = null) : AppError

    /** The format is not one MyLibrary can decode. */
    data class UnsupportedFormat(val mimeType: String?, val extension: String? = null) : AppError

    /** The archive or document is encrypted and the password is wrong or missing. */
    data class PasswordRequired(val wrongPassword: Boolean = false) : AppError

    /** The document is DRM-protected or otherwise locked against reading. */
    data object Protected : AppError

    /** Not enough memory to open or render the document. */
    data object OutOfMemory : AppError

    /** The document has no pages, chapters or entries to display. */
    data object EmptyDocument : AppError

    /** Anything else; carries the original cause for logging. */
    data class Unexpected(val cause: Throwable) : AppError
}
