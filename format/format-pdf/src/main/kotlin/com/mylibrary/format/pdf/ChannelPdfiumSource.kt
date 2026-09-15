package com.mylibrary.format.pdf

import io.legere.pdfiumandroid.api.PdfiumSource
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How many consecutive no-progress reads to tolerate before giving up on a chunk.
 *
 * A `FileChannel` only returns 0 for a non-empty remaining buffer if something unusual happened
 * underneath, but "unusual" here means an unbounded loop inside a decoder, so the retries are
 * bounded instead.
 */
private const val MAX_ZERO_READS = 4

/**
 * Presents a [SeekableByteChannel] to pdfium as a random-access source.
 *
 * This is the whole point of `PdfEngine` reading through `DocumentSource.openChannel()` rather than
 * `openStream()`: a PDF's cross-reference table sits at the *end* of the file, so pdfium seeks
 * around it constantly. Reading it into a `ByteArray` first — which the library's `newDocument`
 * overloads also offer, and which is how most pdfium bindings are used — would put an entire book,
 * tens of megabytes for a scanned one, on the heap before a single page is drawn, and a second copy
 * on top while opening. Handing pdfium a seekable channel instead keeps memory proportional to the
 * page being rendered.
 *
 * pdfium's `FPDF_LoadCustomDocument` contract is met by [read]'s shape: it asks for a bounded range
 * at an absolute offset and is promised exactly [size] bytes unless the file ends first. The
 * accesses are not concurrent — pdfium issues them from whichever thread is inside a render call,
 * and [PdfDocument] serialises those behind one mutex — so the channel needs no lock of its own.
 *
 * @param declaredLength fallback for [length] when the channel refuses to report a size, which
 *   `content://` providers occasionally do. pdfium uses the length to bound its reads, so a length
 *   that is too short truncates the document while one that is too long merely costs a failed read.
 */
internal class ChannelPdfiumSource(
    private val channel: SeekableByteChannel,
    declaredLength: Long,
) : PdfiumSource {

    private val closed = AtomicBoolean(false)

    override val length: Long =
        runCatching { channel.size() }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: declaredLength

    override fun read(position: Long, buffer: ByteArray, size: Int): Int {
        if (closed.get() || size <= 0) return 0

        // wrap() leaves the buffer's array offset alone and constrains position/limit to the
        // requested window, so the channel fills buffer[0 until size] as the contract requires.
        val target: ByteBuffer = ByteBuffer.wrap(buffer, 0, size)
        return try {
            channel.position(position)
            var total = 0
            var stalls = 0
            while (target.hasRemaining() && stalls < MAX_ZERO_READS) {
                val read = channel.read(target)
                if (read < 0) break
                if (read == 0) {
                    stalls++
                } else {
                    total += read
                }
            }
            total
        } catch (error: Exception) {
            // pdfium reads through a native frame: letting this escape would unwind into C++ with
            // no handler. A negative result is the interface's "this read failed" signal.
            -1
        }
    }

    /**
     * Releases the channel. [io.legere.pdfiumandroid.PdfDocument.close] calls this for us, so it
     * also has to survive being called again by the failure paths in `PdfEngine.open`.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { channel.close() }
    }
}
