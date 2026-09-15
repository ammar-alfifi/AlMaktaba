package com.mylibrary.format.archive

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * One opened comic container, reduced to what the reader needs: the page entries in reading order,
 * and the encoded bytes of a single page on demand.
 *
 * CBZ and CBR both arrive through this interface, so page ordering, downsampling and error mapping
 * are written once and behave identically for the two containers. Instances are created by
 * [ArchiveEngine.open] and owned by [ArchiveDocument].
 *
 * [readPage] blocks, because it reads (and for RAR decompresses) from the container. It is not a
 * `suspend` function on purpose: [ArchiveDocument.pageSize] needs it and the domain fixes that
 * method as non-suspending. Callers must therefore treat the *first* call for a page as real work
 * and keep it off the main thread; results are memoised above this layer.
 */
internal interface ArchiveSource : AutoCloseable {

    /**
     * Entry names in reading order.
     *
     * Names are the container's raw ones — not normalized — because they are what the container
     * must be asked for when the page is read back.
     */
    val pageNames: List<String>

    /**
     * The encoded (still compressed) bytes of page [pageIndex].
     *
     * @throws PageReadException when the entry is missing, unreadable, or implausibly large.
     */
    fun readPage(pageIndex: Int): ByteArray

    /** Releases the container and any temporary copy of it. Safe to call more than once. */
    override fun close()
}

/**
 * A page could not be read out of its container, or held no decodable image.
 *
 * Mapped to `AppError.CorruptDocument` for that *page* only. One damaged page in a downloaded comic
 * must not take the whole book down: the other 199 pages are perfectly readable.
 */
internal class PageReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The container is encrypted and needs a password the caller has not supplied, or supplied wrongly.
 *
 * Container-neutral on purpose: the engine turns it into `AppError.PasswordRequired`, so the reader
 * can prompt for a password without knowing whether it was a RAR or something else that asked.
 */
internal class PasswordRequiredException(val wrongPassword: Boolean, message: String) : Exception(message)

/**
 * Upper bound on the encoded bytes of one page.
 *
 * Even an uncompressed 4K scan stays in the tens of megabytes; the cap exists so a malformed or
 * hostile archive cannot make the reader allocate an unbounded buffer by declaring a multi-gigabyte
 * entry (the classic zip bomb). 64 MiB leaves two orders of magnitude of headroom over a real page.
 */
internal const val MAX_PAGE_BYTES: Int = 64 * 1024 * 1024

/** Copy buffer size; big enough to keep the decompression loop off the per-syscall path. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Reads [input] to its end, refusing to buffer more than [limit] bytes.
 *
 * The limit is checked while reading rather than up front because a streamed zip entry carries no
 * reliable size in its local header — the size is written *after* the data, in a data descriptor —
 * so the only trustworthy place to catch an oversized entry is as its bytes arrive.
 */
internal fun readEntryBytes(input: InputStream, limit: Int = MAX_PAGE_BYTES): ByteArray {
    val out = ByteArrayOutputStream(minOf(COPY_BUFFER_BYTES, limit))
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) {
            throw PageReadException("Archive entry is larger than the ${limit / (1024 * 1024)} MiB page limit")
        }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
