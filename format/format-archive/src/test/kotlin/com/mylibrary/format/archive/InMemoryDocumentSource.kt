package com.mylibrary.format.archive

import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.model.BookFormat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * A [DocumentSource] over a byte array, so the tests need no files and no `content://` provider.
 *
 * The channel is real rather than a `UnsupportedOperationException`: a CBR copies its source through
 * [DocumentSource.openChannel], and a fake that cannot serve a channel would make the RAR path
 * untestable even in the one place it can be tested without a RAR fixture.
 */
internal class InMemoryDocumentSource(
    private val bytes: ByteArray,
    override val displayName: String,
    override val format: BookFormat,
    override val id: String = "test://${format.fileExtension}/$displayName",
) : DocumentSource {

    override val mimeType: String = format.mimeTypes.first()

    override val sizeBytes: Long = bytes.size.toLong()

    override fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun openChannel(): SeekableByteChannel = ByteArrayChannel(bytes)
}

/** A read-only, seekable channel over a byte array — the shape SAF providers implement. */
private class ByteArrayChannel(private val bytes: ByteArray) : SeekableByteChannel {

    private var cursor = 0L
    private var open = true

    override fun read(destination: ByteBuffer): Int {
        if (cursor >= bytes.size) return -1
        val count = minOf(destination.remaining(), bytes.size - cursor.toInt())
        destination.put(bytes, cursor.toInt(), count)
        cursor += count
        return count
    }

    override fun write(source: ByteBuffer): Int = throw UnsupportedOperationException("Read-only source")

    override fun position(): Long = cursor

    override fun position(newPosition: Long): SeekableByteChannel {
        cursor = newPosition
        return this
    }

    override fun size(): Long = bytes.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("Read-only source")

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }
}
