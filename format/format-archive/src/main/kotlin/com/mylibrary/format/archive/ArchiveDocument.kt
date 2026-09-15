package com.mylibrary.format.archive

import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.PageImage
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.TocEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * An opened comic archive.
 *
 * **Pages are decoded on demand and never kept.** A 200-page comic is gigabytes of ARGB, so the
 * document holds no decoded page at all: [renderPage] reads one entry out of the container, decodes
 * it, hands the pixels to the caller, and forgets it. The byte budget for holding pages belongs to
 * the reader's own cache, which is the layer that knows how much memory the device has and which
 * pages the user is about to turn to; a second cache down here would fight it and double the
 * footprint.
 *
 * **What *is* remembered** is one [PageSize] per page — two `Int`s, a few hundred bytes for a whole
 * book. Sizes cost one header-only read of the entry the first time a page's size is asked for, and
 * the reader asks on every scroll, so caching them keeps [pageSize] cheap after the first call.
 *
 * **Failure is per page.** A page whose entry will not decode comes back as
 * `AppError.CorruptDocument` while the document stays open and every other page keeps working: one
 * damaged scan in a downloaded book is normal, and losing the whole book over it is not.
 *
 * Instances are created by [ArchiveEngine.open] and own their [ArchiveSource]; [close] releases it.
 */
class ArchiveDocument internal constructor(
    /**
     * The container that was actually decoded, which is not necessarily the format the caller
     * declared — see [ArchiveEngine.open].
     */
    override val format: BookFormat,
    private val archive: ArchiveSource,
    private val ioDispatcher: CoroutineDispatcher,
    override val metadata: DocumentMetadata = DocumentMetadata(),
) : PagedDocument {

    /**
     * A comic has an image per page and no text layer at all, so the search, text-extraction and
     * outline affordances are all switched off, and [search] and [pageText] have nothing to say.
     */
    override val capabilities: EngineCapabilities = EngineCapabilities(
        canSearch = false,
        canExtractText = false,
        canRenderPages = true,
        hasOutline = false,
        requiresPassword = false,
    )

    /** A comic archive carries no table of contents; pages are addressed by number. */
    override val outline: List<TocEntry> = emptyList()

    override val pageCount: Int get() = archive.pageNames.size

    private val pageSizes = ConcurrentHashMap<Int, PageSize>()

    /** Set before the container is released, so a render already in flight can fail cleanly. */
    @Volatile
    private var closed = false

    /**
     * The page's intrinsic size, from its header rather than from its pixels.
     *
     * The first call for a page reads the entry and parses its header, so it does real I/O and
     * belongs off the main thread; afterwards the answer is two cached `Int`s and can be called as
     * often as the reader likes. A page whose entry cannot be read reports `PageSize(0, 0)` — the
     * document genuinely does not know the page's shape, and the interface has no way to say so —
     * which the reader should treat as "unknown, show a placeholder". [renderPage] reports the real
     * problem when the page is displayed; this method never throws for it, because a size that
     * cannot be learnt must not take down a scrolling list.
     */
    override fun pageSize(pageIndex: Int): PageSize {
        requirePageIndex(pageIndex)
        pageSizes[pageIndex]?.let { return it }

        val size = try {
            PageDecoder.bounds(archive.readPage(pageIndex))
        } catch (_: Exception) {
            PageSize(UNKNOWN_PAGE_DIMENSION, UNKNOWN_PAGE_DIMENSION)
        }

        // Races are possible here (the reader may size two pages at once); the value is deterministic
        // either way, and this keeps every caller from re-reading the entry.
        val cached = pageSizes.putIfAbsent(pageIndex, size)
        return cached ?: size
    }

    override suspend fun renderPage(request: PageRenderRequest): AppResult<PageImage> {
        requirePageIndex(request.pageIndex)
        if (closed) {
            // A render that was already in flight when the reader left the screen: expected, and
            // not a reason to crash.
            return AppResult.Failure(AppError.FileAccess("The archive has been closed"))
        }
        return withContext(ioDispatcher) {
            try {
                val bytes = archive.readPage(request.pageIndex)
                AppResult.Success(PageDecoder.decode(bytes, request))
            } catch (cancellation: CancellationException) {
                // Cancellation is control flow: the reader scrolled away, this is not a failure.
                throw cancellation
            } catch (outOfMemory: OutOfMemoryError) {
                // The one failure this engine is most likely to hit in the field, and the one the
                // UI has a specific answer for (drop the caches, retry smaller).
                AppResult.Failure(AppError.OutOfMemory)
            } catch (damaged: PageReadException) {
                AppResult.Failure(AppError.CorruptDocument(damaged.message))
            } catch (unreadable: IOException) {
                AppResult.Failure(AppError.FileAccess(unreadable.message))
            } catch (error: Throwable) {
                AppResult.Failure(AppError.Unexpected(error))
            }
        }
    }

    /**
     * Always `null`. A comic page is a picture with no text layer, and returning an empty string
     * instead would make the reader offer a search that can never return a result. The `null` is the
     * domain's way of saying "this format has no text", which is also what keeps [search] honest.
     */
    override suspend fun pageText(pageIndex: Int): String? = null

    /**
     * Always empty. [capabilities] reports `canSearch = false`, so the reader never offers search;
     * returning an empty list rather than throwing means a stale call from a previous screen cannot
     * crash the app.
     */
    override suspend fun search(query: String, limit: Int): List<SearchHit> = emptyList()

    /**
     * Releases the container, and for a CBR the temporary copy of it. Safe to call twice, and safe
     * to call while a render is in flight — the render then fails with `AppError.FileAccess` rather
     * than reading a half-closed archive.
     */
    override fun close() {
        if (closed) return
        closed = true
        pageSizes.clear()
        archive.close()
    }

    /**
     * An index outside the document is a caller bug, not a document failure — the reader clamps the
     * indices it derives from a slider and a page counter — so it throws instead of coming back as
     * an `AppResult` that would have to describe it as damage. `AppError` has no case for "you asked
     * for the wrong page", and reporting `CorruptDocument` would blame the user's file for a bug in
     * the caller.
     */
    private fun requirePageIndex(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) {
            throw IndexOutOfBoundsException("Page $pageIndex is outside 0 until $pageCount")
        }
    }

    private companion object {
        /** What [pageSize] reports for a page whose entry cannot be read. */
        const val UNKNOWN_PAGE_DIMENSION = 0
    }
}
