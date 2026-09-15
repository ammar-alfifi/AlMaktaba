package com.mylibrary.format.archive

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.rarfile.FileHeader
import com.mylibrary.core.domain.engine.DocumentSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * A CBR: a RAR archive full of images, read with junrar.
 *
 * **What junrar 8.1.0 actually offers, and what was chosen.** `com.github.junrar.Archive` has
 * constructors taking a `java.io.File`, a `java.io.InputStream`, or a
 * `(VolumeManager, ArchiveOptions)` pair. There is **no** `SeekableByteChannel` constructor, which
 * matters because RAR finds its headers and compressed blocks by seeking — every constructor ends up
 * in the same place, a junrar-internal `SeekableReadOnlyByteChannel`:
 *
 *  - The `InputStream` constructor wraps the stream in `RandomAccessInputStream`, which buffers the
 *    *entire* archive in a `byte[]`. For a 200-page comic that is the whole book in RAM, on top of
 *    the page being decoded, so it is exactly the allocation this engine exists to avoid. Rejected.
 *  - The `(VolumeManager, ArchiveOptions)` constructor is public and would accept a hand-written
 *    `VolumeManager` backed by [DocumentSource.openChannel], avoiding the temp copy entirely. It is
 *    the least-travelled path in the library, it is untestable here (no RAR encoder exists in the
 *    toolchain or on the CI image to build a fixture), and a subtle mistake in it would break CBR
 *    silently. Rejected as a needless risk for a case junrar's own test suite does not cover.
 *  - The `File` constructor is what every junrar user exercises. So the document source is copied,
 *    once, into a per-document directory under the caller's cache directory, and **the copy is
 *    deleted in [close]**.
 *
 * The copy streams through [DocumentSource.openChannel] into the file with a fixed buffer, so the
 * archive is never held in a single byte array; the only extra cost is temporary disk space equal to
 * the book, in the cache directory the system is free to reclaim.
 *
 * **Thread safety.** A junrar `Archive` is not safe for concurrent use — it shares one decompressor
 * and one read cursor between calls — while the reader may well render page 3 and prefetch page 4 at
 * the same time. Every call into the archive is therefore serialised on [lock]. The lock is held
 * across blocking I/O by design: [ArchiveSource.readPage] is a plain function because
 * [ArchiveDocument.pageSize] cannot suspend, so a coroutine mutex is not an option here.
 */
internal class RarArchiveSource private constructor(
    private val archive: Archive,
    private val headers: List<FileHeader>,
    private val temporaryFile: File,
) : ArchiveSource {

    private val lock = Any()
    private var closed = false

    override val pageNames: List<String> = headers.map { it.fileName }

    override fun readPage(pageIndex: Int): ByteArray = synchronized(lock) {
        if (closed) throw PageReadException("The archive has been closed")
        val header = headers.getOrNull(pageIndex)
            ?: throw PageReadException("Page $pageIndex is outside 0 until ${headers.size}")

        val declaredSize = header.fullUnpackSize
        if (declaredSize <= 0L) return@synchronized ByteArray(0)
        if (declaredSize > MAX_PAGE_BYTES) {
            throw PageReadException("Entry '${header.fileName}' declares $declaredSize bytes, past the page limit")
        }

        // extractFile rather than getInputStream: the latter streams through a pipe fed by a
        // background thread that swallows RarException, so a page whose password is wrong or whose
        // data is damaged would arrive as a short read and be reported as "not an image" instead of
        // as what it is.
        val out = ByteArrayOutputStream(minOf(declaredSize, INITIAL_CAPACITY_BYTES.toLong()).toInt())
        try {
            archive.extractFile(header, out)
        } catch (error: RarException) {
            throw PageReadException("Entry '${header.fileName}' could not be extracted", error)
        } catch (error: IOException) {
            throw PageReadException("Entry '${header.fileName}' could not be extracted", error)
        }
        return@synchronized out.toByteArray()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                archive.close()
            } catch (_: IOException) {
                // A failed close of the reader leaks nothing that matters: the temporary copy below
                // is the resource that has to go.
            }
            deleteTemporaryCopy(temporaryFile)
        }
    }

    companion object {

        /** The copy's file name inside its per-document directory. */
        private const val TEMPORARY_FILE_NAME = "comic.rar"

        /** Prefix identifying the per-document directories this class is allowed to remove. */
        private const val TEMPORARY_DIRECTORY_PREFIX = "cbr-"

        /** Longest sanitised fragment of the document id used in a directory name. */
        private const val ID_FRAGMENT_CHARS = 48

        /** Copy buffer for the temp copy; large enough to keep the syscall count down. */
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Initial extraction buffer; the container's declared size may be far smaller than the cap. */
        private const val INITIAL_CAPACITY_BYTES = 1024 * 1024

        /**
         * Opens [documentSource], copying it into [cacheDirectory] first.
         *
         * @param password `null` when the caller has none; the archive then reports
         *   [PasswordRequiredException] if it turns out to need one.
         * @throws RarException when the bytes are not a RAR archive, are damaged, or are encrypted
         *   in a way that fails during header parsing. The engine maps these to `AppError`.
         * @throws PasswordRequiredException when the archive is encrypted and [password] is missing
         *   or wrong.
         * @throws IOException when the copy cannot be written.
         */
        fun open(documentSource: DocumentSource, cacheDirectory: File, password: String?): RarArchiveSource {
            val temporaryFile = copyToCache(documentSource, cacheDirectory)
            var archive: Archive? = null
            try {
                archive = if (password == null) Archive(temporaryFile) else Archive(temporaryFile, password)
                val headers = archive.fileHeaders
                    .filter { ComicPageOrder.isPage(it.fileName, it.isDirectory) }
                    .sortedWith(compareBy(ComicPageOrder.comparator) { it.fileName })
                verifyPassword(archive, headers, password)
                return RarArchiveSource(archive, headers, temporaryFile)
            } catch (error: Throwable) {
                // Whatever went wrong, the copy on disk is ours to remove: leaving it would let a
                // folder of unreadable comics silently fill the device's cache.
                try {
                    archive?.close()
                } catch (_: IOException) {
                    // Already on the failure path; the delete below is what matters.
                }
                deleteTemporaryCopy(temporaryFile)
                throw error
            }
        }

        /**
         * Fails with [PasswordRequiredException] unless the archive is readable with [password].
         *
         * junrar only discovers a password problem when it decrypts something. Header-encrypted
         * archives therefore already failed in the `Archive` constructor above, with
         * `WrongPasswordException` ("Missing password for header-encrypted RAR5 archive", or "RAR5
         * password check failed"). This handles the other case: RAR4 archives whose *files* are
         * encrypted, where the headers are readable and a wrong password only shows up as garbage —
         * a CRC error — while extracting.
         *
         * Probing with the smallest page at open time costs one small extraction and buys the reader
         * an accurate password prompt instead of an unexplained decode failure on page 1. A failure
         * here is reported as a wrong password rather than as damage, because the archive has
         * already declared itself encrypted.
         */
        private fun verifyPassword(archive: Archive, headers: List<FileHeader>, password: String?) {
            if (!archive.isPasswordProtected()) return
            if (password == null) {
                throw PasswordRequiredException(wrongPassword = false, message = "The archive is password-protected")
            }
            val probe = headers.minByOrNull { it.fullUnpackSize } ?: return
            val readable = try {
                archive.extractFile(probe, NullOutputStream)
                true
            } catch (_: RarException) {
                false
            } catch (_: IOException) {
                false
            }
            if (!readable) {
                throw PasswordRequiredException(wrongPassword = true, message = "The supplied password was rejected")
            }
        }

        /**
         * Copies the source into `<cacheDirectory>/<per-document directory>/comic.rar`.
         *
         * The copy goes through [DocumentSource.openChannel] with a fixed buffer, so the archive is
         * never materialised in memory. The directory is per document, so two books open at once
         * cannot collide, and so removing one never touches the other.
         */
        private fun copyToCache(documentSource: DocumentSource, cacheDirectory: File): File {
            val directory = File(cacheDirectory, temporaryDirectoryName(documentSource.id))
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Cannot create archive cache directory ${directory.path}")
            }
            val target = File(directory, TEMPORARY_FILE_NAME)
            try {
                documentSource.openChannel().use { channel ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                        while (channel.read(buffer) != -1) {
                            buffer.flip()
                            out.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
                            buffer.clear()
                        }
                    }
                }
            } catch (error: Throwable) {
                deleteTemporaryCopy(target)
                throw error
            }
            return target
        }

        /**
         * A filesystem-safe directory name derived from [documentId].
         *
         * A document id is typically a `content://` URI, so it can contain `/`, `:` and percent
         * escapes that mean nothing to a path, and two different ids can sanitise to the same text.
         * The trailing hash keeps them apart.
         */
        private fun temporaryDirectoryName(documentId: String): String {
            val safe = buildString {
                documentId.take(ID_FRAGMENT_CHARS).forEach { char ->
                    val allowed = char.isLetterOrDigit() || char == '-' || char == '_' || char == '.'
                    append(if (allowed) char else '_')
                }
            }
            return "$TEMPORARY_DIRECTORY_PREFIX$safe-${documentId.hashCode().toUInt().toString(16)}"
        }

        /** Removes the temporary copy and the (now empty) per-document directory holding it. */
        private fun deleteTemporaryCopy(temporaryFile: File) {
            val directory = temporaryFile.parentFile
            // Delete the copy only if it is ours: the delete could otherwise be pointed at a path
            // the caller handed us.
            if (temporaryFile.name == TEMPORARY_FILE_NAME) {
                temporaryFile.delete()
                if (directory != null && directory.name.startsWith(TEMPORARY_DIRECTORY_PREFIX)) {
                    // Fails harmlessly when the directory is not empty, which is what we want.
                    directory.delete()
                }
            }
        }
    }
}

/**
 * Discards bytes, so a password probe does not have to allocate the page it is testing.
 *
 * `OutputStream.nullOutputStream()` is Java 11 and only exists on Android 33+, so this is the
 * minSdk-26-safe equivalent.
 */
private object NullOutputStream : OutputStream() {
    override fun write(byte: Int) = Unit

    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}
