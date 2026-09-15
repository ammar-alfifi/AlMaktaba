package com.mylibrary.format.text

import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Chapter
import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.TocEntry
import com.mylibrary.format.text.internal.ChapterIndex
import com.mylibrary.format.text.internal.PlainTextMarkup
import com.mylibrary.format.text.internal.census
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * An opened plain-text book: one decoded string, and an index of where its chapters are.
 *
 * Created by [TextEngine], which owns charset detection and the error mapping; this class only
 * serves the decoded result. It is deliberately cheap to construct and holds no file handle —
 * decoding is complete by the time it exists, so a TXT book survives its storage grant being revoked
 * mid-read, and nothing here can fail asynchronously.
 *
 * ## Chapters
 *
 * A TXT file has no chapters. What it has is form feeds, in the files whose producer cared, and
 * paragraph breaks otherwise; [ChapterIndex] turns either into offsets. [Chapter.title] is `null`
 * for every chapter and that is a decision, not an omission: inventing "Chapter 1" here would put a
 * string in the domain layer that no resource file can translate, and TXT genuinely has no title to
 * show. The UI renders its own localised "Part N" from the index it already has.
 *
 * @param charsetName the encoding the file was decoded with, kept for diagnostics — a bug report
 *   that says "mojibake" is answered by its value, which the reader can show in book details.
 */
class PlainTextDocument internal constructor(
    override val metadata: DocumentMetadata,
    val charsetName: String,
    private val chapters: ChapterIndex,
    text: String,
) : ReflowableDocument {

    /**
     * The decoded book, or `null` once closed.
     *
     * `@Volatile` because the reader may search on a background dispatcher while rendering a chapter
     * on another thread; publishing the book safely matters more than the nanoseconds it costs.
     */
    @Volatile
    private var text: String? = text

    override val format: BookFormat get() = BookFormat.TXT

    /**
     * TXT can do everything a reflowable text can do except have resources or an outline: there is
     * no markup to reference an image from, and no title to put in a table of contents.
     */
    override val capabilities: EngineCapabilities = EngineCapabilities(
        canSearch = true,
        canExtractText = true,
        canRenderPages = false,
        hasOutline = false,
    )

    override val outline: List<TocEntry> = emptyList()

    override val chapterCount: Int get() = chapters.count

    override fun chapter(index: Int): Chapter {
        checkIndex(index)
        return Chapter(
            index = index,
            title = null,
            locator = ReadingLocator.Reflowable(chapterIndex = index, charOffset = 0),
        )
    }

    /**
     * The chapter as HTML, in the shape the reader renders every reflowable format in.
     *
     * The script direction is detected here, per chapter, from the chapter's own text: it is what
     * makes an English TXT file read left to right inside MyLibrary's Arabic interface, and an
     * Arabic TXT file read right to left inside an English one. A chapter is bounded by the index,
     * so this is a fixed amount of work no matter how large the book is.
     */
    override suspend fun chapterHtml(index: Int): String {
        val content = requireText()
        checkIndex(index)
        val from = chapters.start(index)
        val to = chapters.end(index)
        val direction = census(content, from, to).direction
        return PlainTextMarkup.renderChapter(content, from, to, direction)
    }

    /**
     * The chapter's plain text.
     *
     * This is the string every character offset in the document refers to — including
     * [SearchHit.locator] — which is why it is a plain substring of the decoded book rather than
     * anything the HTML renderer has touched: an offset that indexes one string and a snippet taken
     * from another would drift apart the moment a chapter was edited, escaped or re-wrapped.
     */
    override suspend fun chapterText(index: Int): String {
        val content = requireText()
        checkIndex(index)
        return content.substring(chapters.start(index), chapters.end(index))
    }

    /** Always `null`: a TXT file has no resources to resolve. */
    override suspend fun resource(path: String): ByteArray? = null

    /**
     * Finds [query] case-insensitively, in reading order.
     *
     * The scan runs on [Dispatchers.Default] because it is CPU-bound and unbounded — the whole book
     * is examined, and a book is tens of megabytes — and `search` is `suspend` precisely so it can
     * leave the main thread. It stays cancellable throughout: a new query cancels the old scan
     * within one chapter, so typing does not queue up scans of books the user has moved past.
     *
     * Each chapter is scanned inside its own boundaries, so a match that spans the break between two
     * chapters is not reported. That is deliberate: the break is not part of either chapter's text,
     * so there would be no offset the reader could highlight.
     *
     * The text is read before the first suspension, so a [close] that races with a scan already under
     * way cannot turn into a failure halfway through it — a closed document only fails a search that
     * *starts* after it was closed, which is a caller bug.
     */
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val content = requireText()
        if (query.isBlank() || limit <= 0) return emptyList()

        return withContext(Dispatchers.Default) {
            val hits = ArrayList<SearchHit>(minOf(limit, INITIAL_HIT_CAPACITY))
            for (chapterIndex in 0 until chapters.count) {
                coroutineContext.ensureActive()

                val chapterStart = chapters.start(chapterIndex)
                val chapterEnd = chapters.end(chapterIndex)
                val label = (chapterIndex + 1).toString()
                var searchFrom = chapterStart

                while (hits.size < limit) {
                    val matchStart = content.indexOf(query, searchFrom, ignoreCase = true)
                    if (matchStart < 0 || matchStart >= chapterEnd) break
                    val matchEnd = matchStart + query.length
                    // A match that runs past this chapter's end cannot be a hit; neither can any
                    // match starting later, since every match is the same length.
                    if (matchEnd > chapterEnd) break

                    val snippetStart = (matchStart - SNIPPET_PADDING).coerceAtLeast(chapterStart)
                    val snippetEnd = (matchEnd + SNIPPET_PADDING).coerceAtMost(chapterEnd)
                    hits += SearchHit(
                        locator = ReadingLocator.Reflowable(chapterIndex, matchStart - chapterStart),
                        label = label,
                        snippet = content.substring(snippetStart, snippetEnd),
                        matchStart = matchStart - snippetStart,
                        matchEnd = matchEnd - snippetStart,
                    )
                    searchFrom = matchEnd
                }

                if (hits.size >= limit) break
            }
            hits
        }
    }

    /**
     * Releases the decoded book.
     *
     * Idempotent, because the reader closes when it leaves the screen and again when the process is
     * being torn down. The decoded text is the only thing worth releasing here — a 20 MB book is
     * 20 MB of heap, and dropping the reference lets the collector reclaim it before this object
     * itself becomes unreachable — and it is dropped rather than kept for a "just in case" read,
     * since a closed document that silently answered from a stale buffer would hide the bug that
     * closed it. The chapter index stays: it is a few kilobytes, and it keeps [chapterCount] and
     * [chapter] answerable for a UI that is still animating out.
     */
    override fun close() {
        text = null
    }

    private fun requireText(): String = checkNotNull(text) {
        "This PlainTextDocument has been closed; open the source again to read it"
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until chapters.count) {
            "Chapter $index is out of bounds: this document has ${chapters.count} chapters"
        }
    }

    private companion object {
        /** Half of the eighty-character window a hit is reported in. */
        const val SNIPPET_PADDING = 40

        const val INITIAL_HIT_CAPACITY = 32
    }
}
