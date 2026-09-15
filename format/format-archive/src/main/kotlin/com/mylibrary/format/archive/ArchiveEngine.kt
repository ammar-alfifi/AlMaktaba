package com.mylibrary.format.archive

import android.content.Context
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.WrongPasswordException
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.common.fileStem
import com.mylibrary.core.domain.engine.DocumentEngine
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.DocumentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipException

/**
 * The comic-book engine: CBZ (zip) and CBR (rar) archives of page images.
 *
 * Both containers produce exactly the same thing — a list of image entries in reading order — so the
 * two differ only in how bytes are fetched from them: `java.util.zip` for a CBZ, junrar for a CBR.
 * Everything above that (ordering, downsampling, error mapping, the document itself) is shared, and
 * [ComicPageOrder] is the single place that decides what counts as a page and in what order.
 *
 * **Mislabelled archives.** The bytes are sniffed before they are trusted. Comic archives are
 * routinely shared with the wrong extension — a RAR that someone renamed to `.cbz` because their
 * reader only accepts that — and if the extension were believed, the zip reader would report an
 * empty or corrupt document for a file that is perfectly readable. A `Rar!` header therefore wins
 * over a `.cbz` name, and a `PK` header wins over a `.cbr` name; only when neither signature is
 * present does the declared format decide, which keeps the error message specific ("not a RAR
 * archive") instead of a generic one. [ArchiveDocument.format] reports the container that was
 * actually decoded.
 *
 * **Errors never escape [open].** Every failure a user can cause — a truncated download, a
 * password-protected archive, a file that is not an archive at all — comes back as an [AppError]
 * the reader can put into words, and only genuine programming errors throw.
 *
 * This class is stateless and safe to share: each [open] returns an independent document.
 */
class ArchiveEngine(
    /**
     * Where a CBR's temporary working copy is written — see [RarArchiveSource] for why a RAR needs
     * one. Only CBR touches this directory; a CBZ is read entirely through the document source.
     */
    private val cacheDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentEngine {

    /**
     * Android entry point: keeps the working copies under the app's own cache directory, which the
     * system is free to reclaim when storage runs short and which disappears with the app.
     *
     * The application context is taken here, in the constructor, so an engine built from an Activity
     * cannot pin it for the process's lifetime.
     */
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        cacheDirectory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY_NAME),
        ioDispatcher = ioDispatcher,
    )

    /**
     * True for both comic containers. Also true for a file whose extension says one thing and whose
     * bytes say another, which is the point: [open] sniffs rather than trusting the label.
     */
    override fun supports(format: BookFormat): Boolean =
        format == BookFormat.CBZ || format == BookFormat.CBR

    override suspend fun open(source: DocumentSource, password: String?): AppResult<OpenDocument> =
        withContext(ioDispatcher) {
            try {
                val container = containerOf(source)
                val archive = when (container) {
                    BookFormat.CBR -> RarArchiveSource.open(source, cacheDirectory, password)
                    else -> ZipArchiveSource.open(source)
                }

                if (archive.pageNames.isEmpty()) {
                    // No images at all: an archive of text files, an empty zip, or a file that is
                    // not an archive. Either way there is nothing to read, and saying so once here
                    // saves the reader from opening a document with no pages.
                    archive.close()
                    AppResult.Failure(AppError.EmptyDocument)
                } else {
                    AppResult.Success(
                        ArchiveDocument(
                            format = container,
                            archive = archive,
                            ioDispatcher = ioDispatcher,
                            metadata = DocumentMetadata(title = fileStem(source.displayName)),
                        )
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Broad on purpose: an archive reader is fed arbitrary files, and a comic that
                // cannot be opened must produce a message, never a crash.
                AppResult.Failure(mapOpenFailure(error, password))
            }
        }

    /**
     * The format of the *bytes*, which may contradict [DocumentSource.format].
     *
     * The declared format from [DocumentSource] is used only as the tie-breaker when the signature
     * is unrecognisable, where it decides which reader gets to produce the error and therefore how
     * specific that error is.
     */
    private fun containerOf(source: DocumentSource): BookFormat {
        val signature = readSignature(source)
        return when {
            signature.matches(RAR_SIGNATURE) -> BookFormat.CBR
            signature.matches(ZIP_SIGNATURE) -> BookFormat.CBZ
            source.format == BookFormat.CBR -> BookFormat.CBR
            else -> BookFormat.CBZ
        }
    }

    /**
     * The first [SIGNATURE_BYTES] bytes of the document, or fewer if it is shorter than that.
     *
     * Read one byte at a time: four syscalls once per open is nothing, and it keeps the loop free of
     * the "a stream may return 0 bytes" case that a bulk read would have to handle.
     */
    private fun readSignature(source: DocumentSource): ByteArray {
        val signature = ByteArray(SIGNATURE_BYTES)
        source.openStream().use { input ->
            for (index in signature.indices) {
                val byte = input.read()
                if (byte < 0) return signature.copyOf(index)
                signature[index] = byte.toByte()
            }
        }
        return signature
    }

    private fun ByteArray.matches(expected: ByteArray): Boolean =
        size >= expected.size && expected.indices.all { this[it] == expected[it] }

    /**
     * Turns whatever went wrong while opening into the error the reader should show.
     *
     * @param password what the caller supplied, which is the only way to tell "this archive needs a
     *   password" from "the password you gave is wrong": junrar reports both with the same exception
     *   types.
     */
    private fun mapOpenFailure(error: Throwable, password: String?): AppError = when (error) {
        is PasswordRequiredException -> AppError.PasswordRequired(wrongPassword = error.wrongPassword)
        is PageReadException -> AppError.CorruptDocument(error.message)
        is RarException -> mapRarFailure(error, password)
        // Before the IOException branch: ZipException is one.
        is ZipException -> AppError.CorruptDocument(error.message)
        is OutOfMemoryError -> AppError.OutOfMemory
        // A revoked URI grant arrives as a SecurityException, and a source that went away as an
        // IOException; both are "the file could not be read", not "the file is damaged".
        is SecurityException -> AppError.FileAccess(error.message)
        is IOException -> AppError.FileAccess(error.message)
        else -> AppError.Unexpected(error)
    }

    private fun mapRarFailure(error: RarException, password: String?): AppError {
        // junrar nests the specific failure, so the whole chain is searched rather than just the
        // outermost exception, whose message is usually a generic "failed to read archive".
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        val encryptionFailure = causes.any {
            it is WrongPasswordException ||
                it is InitDeciphererFailedException ||
                it is UnsupportedRarEncryptedException
        }
        return if (encryptionFailure) {
            // Header-encrypted RAR5 archives fail here in the constructor ("Missing password for
            // header-encrypted RAR5 archive", or "RAR5 password check failed"), which is how a
            // password problem is detected before any page is read.
            AppError.PasswordRequired(wrongPassword = !password.isNullOrEmpty())
        } else {
            // Anything else junrar rejects — not a RAR, a damaged header, an unsupported version —
            // is a broken file as far as the reader is concerned.
            AppError.CorruptDocument(error.message)
        }
    }

    private companion object {
        /** Directory under the app cache that holds per-document CBR copies. */
        const val CACHE_DIRECTORY_NAME = "comic-archives"

        /** How many leading bytes are sniffed. */
        const val SIGNATURE_BYTES = 4

        /**
         * `Rar!`, the first four bytes of both RAR4 (`Rar! `) and RAR5
         * (`Rar! `).
         */
        val RAR_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21)

        /**
         * `PK`, which begins every zip: `PK` for an ordinary archive, `PK`
         * for an empty one and `PK` for a spanned one. Only the two bytes that are
         * common to all three are compared, so a CBZ with no entries still reaches the zip reader
         * and comes back as `AppError.EmptyDocument` rather than as the wrong format.
         */
        val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B)
    }
}
