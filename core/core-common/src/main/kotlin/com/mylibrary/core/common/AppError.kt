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

    /**
     * The format is supported, but its decoder could not be initialised on this device.
     *
     * Distinct from [UnsupportedFormat] because the two need different words and imply different
     * things: "MyLibrary does not read this kind of file" versus "this device could not load the
     * component that reads it". It exists because a decoder backed by a native library can fail to
     * load — a missing ABI, exhausted memory — and that failure has to be reportable without
     * taking the rest of the app with it.
     */
    data class DecoderUnavailable(val format: String, val reason: String? = null) : AppError

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
