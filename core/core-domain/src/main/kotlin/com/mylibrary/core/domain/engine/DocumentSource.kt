package com.mylibrary.core.domain.engine

import com.mylibrary.core.domain.model.BookFormat
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * A readable document, described without reference to Android.
 *
 * This interface is the hinge of the architecture. Decoder modules (`:format:*`) read bytes through
 * it, and `:core:core-data` implements it over a `content://` URI using `ContentResolver`. Because
 * the decoder modules only ever see this interface, none of them needs to know what a `Uri` is, and
 * no UI module ever links against a decoder.
 *
 * [openChannel] exists alongside [openStream] because PDF and RAR both need random access — a PDF's
 * cross-reference table sits at the end of the file, so streaming it from the start is O(n) per
 * lookup.
 */
interface DocumentSource {
    /** Stable identity of the underlying document, used for cache keys. */
    val id: String

    /** The file name as the user sees it, e.g. `كتاب.epub`. */
    val displayName: String

    val mimeType: String
    val sizeBytes: Long
    val format: BookFormat

    /** Opens a fresh sequential stream. The caller closes it. */
    fun openStream(): InputStream

    /** Opens a fresh random-access channel. The caller closes it. */
    fun openChannel(): SeekableByteChannel
}
