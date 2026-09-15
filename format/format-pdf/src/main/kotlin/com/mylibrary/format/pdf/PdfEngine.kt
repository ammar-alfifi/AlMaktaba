package com.mylibrary.format.pdf

import android.content.Context
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.DocumentEngine
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.model.BookFormat
import io.legere.pdfiumandroid.PdfDocument as PdfiumDocument
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.PdfPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decodes PDFs with pdfium, through `io.legere:pdfiumandroid`.
 *
 * pdfium is a C library that owns a native document handle per open file, so this class is only the
 * stateless half — it hands out an independent [PdfDocument] per [open] and keeps nothing itself.
 * That is what lets the library screen read a cover while the reader has another book open.
 *
 * @param context held only for its application context, which `PdfiumCore` uses to resolve the
 *   display density it converts page sizes with. A decoder outliving an Activity would be a leak,
 *   hence the application context rather than whatever was passed in.
 */
class PdfEngine(context: Context) : DocumentEngine {

    private val pdfiumCore: PdfiumCore = PdfiumCore(context.applicationContext)

    override fun supports(format: BookFormat): Boolean = format == BookFormat.PDF

    /**
     * Opens [source] and reads the parts of the document the reader needs immediately — page count,
     * metadata, outline — so that the first frame after opening has everything it draws with.
     *
     * The whole body is wrapped rather than only the pdfium call: a provider can fail while a
     * channel is being opened just as easily as pdfium can reject the bytes, and this method
     * promises never to throw.
     */
    override suspend fun open(source: DocumentSource, password: String?): AppResult<OpenDocument> {
        if (!supports(source.format)) {
            return AppResult.Failure(
                AppError.UnsupportedFormat(
                    mimeType = source.mimeType,
                    extension = source.displayName.substringAfterLast('.', "").ifEmpty { null },
                ),
            )
        }

        val pdfiumSource: ChannelPdfiumSource = try {
            ChannelPdfiumSource(source.openChannel(), source.sizeBytes)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return AppResult.Failure(AppError.FileAccess(error.message))
        }

        // pdfium only takes ownership of the source once the document is constructed. Until then a
        // failure would leak the file descriptor, so every failure path below closes it by hand.
        val native: PdfiumDocument = try {
            withContext(Dispatchers.IO) { pdfiumCore.newDocument(pdfiumSource, password) }
        } catch (cancellation: CancellationException) {
            pdfiumSource.close()
            throw cancellation
        } catch (error: Throwable) {
            pdfiumSource.close()
            return AppResult.Failure(error.toOpenError(password))
        }

        return try {
            withContext(Dispatchers.IO) { openDocument(native, password) }
        } catch (cancellation: CancellationException) {
            native.closeQuietly()
            throw cancellation
        } catch (error: Throwable) {
            native.closeQuietly()
            AppResult.Failure(error.toOpenError(password))
        }
    }

    private fun openDocument(native: PdfiumDocument, password: String?): AppResult<OpenDocument> {
        val pageCount = native.getPageCount()
        if (pageCount <= 0) {
            // pdfium opens a header-only PDF happily and then reports no pages. The reader has
            // nothing to show, and "empty" is a better answer than an empty reader screen.
            native.closeQuietly()
            return AppResult.Failure(AppError.EmptyDocument)
        }

        return AppResult.Success(
            PdfDocument(
                native = native,
                pageCount = pageCount,
                metadata = native.getDocumentMeta().toDocumentMetadata(),
                outline = native.getTableOfContents().toOutline(pageCount),
                // pdfium exposes no "is this document encrypted" flag, so the only honest signal is
                // whether the caller had to supply something to get past it.
                passwordRequired = password != null,
            ),
        )
    }
}

/**
 * Maps a pdfium open failure onto the error the UI knows how to explain.
 *
 * The native layer raises exactly two of these itself: `PdfPasswordException` for
 * `FPDF_ERR_PASSWORD`, and a plain `IOException` carrying "File not in PDF format or corrupted."
 * for everything else. The message check is a fallback for the second case, because a document that
 * needs a password but takes the generic path would otherwise be reported as corrupt and the reader
 * would never offer the password prompt — the one open failure a retry can actually fix.
 *
 * [CancellationException] is deliberately absent: cancellation is control flow, and every caller
 * rethrows it before reaching here.
 */
private fun Throwable.toOpenError(password: String?): AppError = when (this) {
    is PdfPasswordException -> AppError.PasswordRequired(wrongPassword = password != null)
    is OutOfMemoryError -> AppError.OutOfMemory
    is IOException ->
        if (message?.contains("password", ignoreCase = true) == true) {
            AppError.PasswordRequired(wrongPassword = password != null)
        } else {
            AppError.CorruptDocument(message)
        }

    else -> AppError.Unexpected(this)
}

/**
 * Releases a native document from a failure path, where throwing would replace the failure the
 * caller is about to be told about with a less useful one.
 */
private fun PdfiumDocument.closeQuietly() {
    runCatching { close() }
}
