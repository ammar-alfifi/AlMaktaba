package com.mylibrary.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.data.cover.CoverWriter
import com.mylibrary.core.data.source.ContentDocumentSource
import com.mylibrary.core.domain.engine.DocumentEngine
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.repository.BookMetadataResult
import com.mylibrary.core.domain.repository.DocumentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Opens books by handing the right decoder the right byte source.
 *
 * This class is the whole reason the decoder modules can stay ignorant of Android and the UI can
 * stay ignorant of decoders. It receives every registered [DocumentEngine] through Hilt's set
 * multibinding, so adding a format is a matter of adding a module that binds one more engine — no
 * `when (format)` anywhere needs to change.
 */
@Singleton
class DocumentRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engines: Set<@JvmSuppressWildcards DocumentEngine>,
    private val coverWriter: CoverWriter,
    private val dispatchers: DispatcherProvider,
) : DocumentRepository {

    override suspend fun open(book: Book, password: String?): AppResult<OpenDocument> =
        withContext(dispatchers.io) {
            val engine = engines.firstOrNull { it.supports(book.format) }
                ?: return@withContext AppResult.Failure(
                    AppError.UnsupportedFormat(mimeType = null, extension = book.format.fileExtension),
                )
            engine.open(sourceFor(book), password)
        }

    override suspend fun readMetadata(book: Book): AppResult<BookMetadataResult> =
        withContext(dispatchers.io) {
            when (val opened = open(book)) {
                is AppResult.Failure -> opened
                is AppResult.Success -> {
                    val document = opened.data
                    try {
                        AppResult.Success(
                            BookMetadataResult(
                                title = document.metadata.title,
                                author = document.metadata.author,
                                language = document.metadata.language,
                                contentCount = contentCountOf(document),
                            ),
                        )
                    } finally {
                        document.close()
                    }
                }
            }
        }

    /**
     * Produces a cover image for [book].
     *
     * Two strategies, in order of quality:
     *
     *  1. the document's own declared cover — an EPUB's publisher artwork is the real cover, and
     *     rendering page one of an EPUB is not even meaningful;
     *  2. otherwise, for a paged document, a rendering of page one, which is exactly what the cover
     *     of a PDF, CBZ or CBR is in practice.
     *
     * Failure here is not a failure of the import: a book with no cover is perfectly usable, so
     * every error path returns success-with-null rather than propagating. Only a genuine
     * out-of-memory while decoding is reported, because that one signals a real problem.
     */
    override suspend fun extractCover(book: Book): AppResult<String?> = withContext(dispatchers.io) {
        val document = open(book).getOrNull() ?: return@withContext AppResult.Success(null)

        try {
            document.coverImage()?.let { bytes ->
                coverWriter.writeCover(book.uri, bytes)?.let { path ->
                    return@withContext AppResult.Success(path)
                }
            }

            val paged = document as? PagedDocument
            if (paged != null && paged.pageCount > 0) {
                renderFirstPageAsCover(paged, book)?.let { path ->
                    return@withContext AppResult.Success(path)
                }
            }

            AppResult.Success(null)
        } catch (outOfMemory: OutOfMemoryError) {
            // Decoding a cover is the first place a huge document can exhaust the heap; the book
            // itself may still open fine at a smaller render size, so this is reported rather than
            // swallowed.
            AppResult.Failure(AppError.OutOfMemory)
        } finally {
            document.close()
        }
    }

    override fun supportsFormat(format: BookFormat): Boolean = engines.any { it.supports(format) }

    /** Renders page one at cover size, or `null` if the page cannot be rendered. */
    private suspend fun renderFirstPageAsCover(paged: PagedDocument, book: Book): String? {
        val pageSize = paged.pageSize(0)
        if (pageSize.width <= 0 || pageSize.height <= 0) return null

        val scale = COVER_WIDTH_PX.toFloat() / pageSize.width
        val request = PageRenderRequest(
            pageIndex = 0,
            targetWidthPx = COVER_WIDTH_PX,
            targetHeightPx = (pageSize.height * scale).toInt().coerceAtLeast(1),
        )

        val image = paged.renderPage(request).getOrNull() ?: return null

        // A second copy of the page is unavoidable here — Bitmap owns its pixels and PageImage
        // holds a plain IntArray — but it happens once per book, at cover size rather than at full
        // page size, and the bitmap is recycled immediately.
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            coverWriter.writeCover(book.uri, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Pages for a paged document, chapters for a reflowable one, `null` for neither. */
    private fun contentCountOf(document: OpenDocument): Int? = when (document) {
        is PagedDocument -> document.pageCount
        is ReflowableDocument -> document.chapterCount
        else -> null
    }

    /**
     * Wraps a book's URI in a [DocumentSource].
     *
     * The MIME type is asked of the content resolver first, because a provider's answer is more
     * authoritative than the format we inferred from the extension at import time — but it is only
     * used for logging and for the archive engine's signature sniffing, never to re-decide the
     * format.
     */
    private fun sourceFor(book: Book): DocumentSource {
        val resolver = context.contentResolver
        val providerMimeType = runCatching { resolver.getType(Uri.parse(book.uri)) }.getOrNull()
        return ContentDocumentSource(
            contentResolver = resolver,
            id = book.uri,
            displayName = book.title,
            mimeType = providerMimeType ?: book.format.mimeTypes.firstOrNull().orEmpty(),
            sizeBytes = book.sizeBytes,
            format = book.format,
        )
    }

    private companion object {
        /** Wider than any grid cell, so the cover is never upscaled on screen. */
        const val COVER_WIDTH_PX = 600
    }
}
