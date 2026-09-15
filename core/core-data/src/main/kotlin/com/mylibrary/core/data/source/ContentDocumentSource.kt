package com.mylibrary.core.data.source

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.model.BookFormat
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * A [DocumentSource] over a Storage Access Framework `content://` URI.
 *
 * This is the only place in MyLibrary that knows a document is backed by a `Uri`. Everything above
 * it — and, importantly, every `:format:*` decoder — sees just [DocumentSource], which is what keeps
 * the decoder modules free of Android concepts and the UI free of decoders.
 *
 * Both openers go through [ContentResolver], so they inherit the user's persisted URI grant and the
 * app still needs no storage permission of any kind.
 */
class ContentDocumentSource(
    private val contentResolver: ContentResolver,
    override val id: String,
    override val displayName: String,
    override val mimeType: String,
    override val sizeBytes: Long,
    override val format: BookFormat,
) : DocumentSource {

    private val uri: Uri = Uri.parse(id)

    override fun openStream(): InputStream =
        contentResolver.openInputStream(uri)
            ?: throw IOException("Content provider returned no stream for $id")

    /**
     * Opens a seekable channel.
     *
     * `ParcelFileDescriptor.AutoCloseInputStream` is used rather than a plain `FileInputStream` over
     * `pfd.fileDescriptor` for one specific reason: it *owns* the descriptor. A plain stream would
     * leave the `ParcelFileDescriptor` wrapper alive, and its finalizer closes the descriptor a
     * second time — by which point the number may have been recycled onto an unrelated file, which
     * is a genuinely nasty intermittent bug. With `AutoCloseInputStream` there is exactly one owner
     * and closing the channel closes the descriptor exactly once.
     */
    override fun openChannel(): SeekableByteChannel {
        val descriptor: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Content provider returned no file descriptor for $id")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
    }

    override fun equals(other: Any?): Boolean = other is ContentDocumentSource && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ContentDocumentSource($displayName, $format)"
}
