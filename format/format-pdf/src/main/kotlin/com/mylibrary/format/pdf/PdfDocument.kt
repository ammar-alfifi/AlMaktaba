package com.mylibrary.format.pdf

import android.graphics.Bitmap
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.PageImage
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.TocEntry
import io.legere.pdfiumandroid.PdfDocument as PdfiumDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** PDF's own unit. pdfium reports page dimensions in points, and asks for a DPI to convert with. */
private const val POINTS_PER_INCH = 72

/** pdfium's "do not paint the canvas" sentinel; it otherwise fills the bitmap with its grey desk. */
private const val NO_CANVAS_FILL = 0

/** Characters of context shown either side of a search hit. */
private const val SNIPPET_CONTEXT_CHARS = 60

/**
 * One opened PDF, owning one pdfium document handle.
 *
 * ### Threading
 *
 * pdfium is not thread-safe per document: two threads rendering two pages of the same document
 * through one handle corrupt each other's page cache. pdfiumandroid hides a process-wide lock
 * behind its facade, but that only serialises the calls it makes — it does not order *our*
 * open/render/close sequences against each other, and a page closed between another thread's
 * `openPage` and its `renderPageBitmap` is still a freed handle. [lock] is what makes a whole
 * sequence atomic, so it is held across every native call below.
 *
 * The work is dispatched to [Dispatchers.IO] first because every one of those calls blocks until
 * pdfium has finished: rendering a 3000px scan takes tens of milliseconds, and on the caller's
 * thread — which for a page turn is the main thread — that is a dropped frame at best and an ANR
 * at worst. `renderPage` has to hold the lock and the dispatcher together, so they are acquired
 * together, lock inside context.
 *
 * ### Lifetime
 *
 * The handle is released by [close] exactly once no matter how many times it is called. The reader
 * closes on leaving the screen and again on process death, and pdfiumandroid's default
 * `AlreadyClosedBehavior.EXCEPTION` turns a second close into an exception rather than a no-op.
 */
class PdfDocument internal constructor(
    private val native: PdfiumDocument,
    override val pageCount: Int,
    override val metadata: DocumentMetadata,
    override val outline: List<TocEntry>,
    passwordRequired: Boolean,
) : PagedDocument {

    override val format: BookFormat = BookFormat.PDF

    /**
     * Every capability is claimed only where there is a call behind it: pdfium's text layer is real
     * (`FPDFText_GetText`, which [pageText] uses and [search] drives), so both text flags are on,
     * and the outline flag follows what the document actually contained rather than what the format
     * allows.
     */
    override val capabilities: EngineCapabilities = EngineCapabilities(
        canSearch = true,
        canExtractText = true,
        canRenderPages = true,
        hasOutline = outline.isNotEmpty(),
        requiresPassword = passwordRequired,
    )

    private val lock = Mutex()
    private val closed = AtomicBoolean(false)

    /**
     * The page's intrinsic size in points, straight out of the document's page tree.
     *
     * Deliberately not suspend: the reader calls this while scrolling to decide what to lay out
     * next, and a page's size must be knowable before its pixels are. pdfium can answer it without
     * loading the page, so this is the cheap path, and it needs no [lock] — it reads no state that
     * this class owns beyond the closed flag.
     *
     * @return `PageSize(0, 0)` when the page is out of range, the document is closed, or pdfium
     *   cannot size the page. This method has no way to report an error, and a zero size is the
     *   signal callers already have to handle for a broken page.
     */
    override fun pageSize(pageIndex: Int): PageSize {
        if (closed.get() || pageIndex !in 0 until pageCount) return PageSize(0, 0)

        return try {
            val size = native.getPageSize(pageIndex, POINTS_PER_INCH)
            PageSize(size.width.coerceAtLeast(0), size.height.coerceAtLeast(0))
        } catch (error: Throwable) {
            // IllegalStateException once the document is closed, RuntimeException when pdfium
            // cannot read the page tree. Neither is worth propagating from a sizing query.
            PageSize(0, 0)
        }
    }

    override suspend fun renderPage(request: PageRenderRequest): AppResult<PageImage> {
        if (request.pageIndex !in 0 until pageCount) {
            return AppResult.Failure(
                AppError.Unexpected(
                    IndexOutOfBoundsException("Page ${request.pageIndex} is outside 0..${pageCount - 1}"),
                ),
            )
        }

        // pageSize reports a closed document the same way it reports a broken page, so the two are
        // told apart here rather than surfacing as "page has no size" for a closed reader.
        if (closed.get()) return AppResult.Failure(closedError())

        val intrinsic = pageSize(request.pageIndex)
        val target = fitPageInBox(
            pageWidth = intrinsic.width,
            pageHeight = intrinsic.height,
            boxWidth = request.targetWidthPx,
            boxHeight = request.targetHeightPx,
        )
        if (target.width <= 0 || target.height <= 0) {
            return AppResult.Failure(
                AppError.CorruptDocument("Page ${request.pageIndex} has no size to render"),
            )
        }

        return withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed.get()) return@withLock AppResult.Failure(closedError())

                var bitmap: Bitmap? = null
                try {
                    val page = native.openPage(request.pageIndex)
                        ?: return@withLock AppResult.Failure(
                            AppError.CorruptDocument("Page ${request.pageIndex} could not be loaded"),
                        )

                    val canvas = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                    bitmap = canvas

                    // A PDF page has no background of its own: pdfium paints only what the content
                    // stream draws and leaves the rest of the bitmap untouched, so a zero-filled
                    // ARGB buffer would show through as black. Erasing first gives the reader its
                    // paper colour, and passing the same value as the page background makes pdfium
                    // agree with it where a page does declare one.
                    canvas.eraseColor(request.backgroundColorArgb)

                    try {
                        page.renderPageBitmap(
                            bitmap = canvas,
                            startX = 0,
                            startY = 0,
                            drawSizeX = target.width,
                            drawSizeY = target.height,
                            renderAnnot = false,
                            textMask = false,
                            canvasColor = NO_CANVAS_FILL,
                            pageBackgroundColor = request.backgroundColorArgb,
                        )
                    } finally {
                        page.releaseQuietly()
                    }

                    val pixels = IntArray(target.width * target.height)
                    canvas.getPixels(pixels, 0, target.width, 0, 0, target.width, target.height)
                    AppResult.Success(PageImage(pixels, target.width, target.height))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: OutOfMemoryError) {
                    AppResult.Failure(AppError.OutOfMemory)
                } catch (error: Throwable) {
                    AppResult.Failure(AppError.Unexpected(error))
                } finally {
                    // The pixels are already copied out; holding the bitmap would pin a full page
                    // of native memory per render until the GC noticed.
                    bitmap?.recycle()
                }
            }
        }
    }

    override suspend fun pageText(pageIndex: Int): String? {
        if (pageIndex !in 0 until pageCount) return null

        return withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed.get()) return@withLock null
                try {
                    extractText(pageIndex)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // A page whose text layer cannot be read has no text as far as the reader is
                    // concerned: page turns must not fail because a search index is unavailable.
                    null
                }
            }
        }
    }

    /**
     * Finds [query] across the document, page by page.
     *
     * The matching runs over the text pdfium extracted rather than over pdfium's own
     * `FPDFText_FindStart`. That API is the natural fit, but its wrapper here returns a
     * `FindResult` even when `FPDFText_FindStart` returned a null handle — which is precisely what
     * it does for a page with no match — and the next call on that wrapper passes the null handle
     * back into pdfium, which dereferences it. Searching the extracted text is the same search over
     * the same characters, leaves no native handle to leak, and cannot take the process down over a
     * word that is not in the book.
     */
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        if (query.isBlank() || limit <= 0) return emptyList()

        val hits = mutableListOf<SearchHit>()
        for (pageIndex in 0 until pageCount) {
            // A search walks every page of a book; the user scrolling away must be able to stop it
            // before the last one.
            currentCoroutineContext().ensureActive()

            val text = pageText(pageIndex) ?: continue
            var from = 0
            while (hits.size < limit) {
                val match = text.indexOf(query, from, ignoreCase = true)
                if (match < 0) break
                hits += text.toSearchHit(pageIndex, match, query.length)
                from = match + query.length
            }
            if (hits.size >= limit) break
        }
        return hits
    }

    /**
     * Releases the native document exactly once.
     *
     * No mutex is taken here: `close` is not suspend and cannot be. It does not need one either —
     * pdfiumandroid holds its own process-wide lock around every native call, so this either runs
     * before an in-flight render or waits behind it, and afterwards calls fail through the
     * library's already-closed guard instead of touching a freed handle. The guard below is what
     * the contract actually asks for: the reader closes on leaving the screen *and* on process
     * death, and the library's default behaviour makes the second close an exception.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { native.close() }
    }

    private fun extractText(pageIndex: Int): String? {
        val page = native.openPage(pageIndex) ?: return null
        val text = page.useQuietly {
            val textPage = page.openTextPage()
            textPage.useQuietly {
                val charCount = textPage.textPageCountChars()
                if (charCount <= 0) null else textPage.textPageGetText(0, charCount)
            }
        }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun closedError(): AppError =
        AppError.Unexpected(IllegalStateException("The document was closed before this call"))
}

/**
 * Builds a snippet around a match, with [SearchHit.matchStart] / [SearchHit.matchEnd] pointing at
 * it inside the snippet rather than inside the page.
 *
 * Line breaks are rewritten one character for one so the offsets survive the rewrite; collapsing
 * runs of whitespace would read better but would shift the highlight onto the wrong words. The
 * leading and trailing context is why the caller does not have to slice the page text itself.
 */
private fun String.toSearchHit(pageIndex: Int, matchStart: Int, matchLength: Int): SearchHit {
    val from = (matchStart - SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
    val to = (matchStart + matchLength + SNIPPET_CONTEXT_CHARS).coerceAtMost(length)
    val snippet = substring(from, to)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')

    return SearchHit(
        locator = ReadingLocator.Paged(pageIndex),
        label = "Page ${pageIndex + 1}",
        snippet = snippet,
        matchStart = matchStart - from,
        matchEnd = matchStart - from + matchLength,
    )
}

/**
 * Closes a pdfium handle without letting the close replace whatever the body was doing.
 *
 * The wrappers throw `IllegalStateException` when their document is already closed, and releasing a
 * page while another thread closes the document is exactly that race — reporting it would hide the
 * failure that actually matters. Nothing is leaked by staying quiet: closing the document releases
 * any page still open on it.
 */
private inline fun <T> Closeable.useQuietly(block: () -> T): T =
    try {
        block()
    } finally {
        releaseQuietly()
    }

private fun Closeable.releaseQuietly() {
    runCatching { close() }
}
