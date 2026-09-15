package com.mylibrary.format.epub

import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Chapter
import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One chapter, sanitised once so that [EpubDocument.chapterHtml] and [EpubDocument.chapterText] agree. */
private class LoadedChapter(val index: Int, val html: String, val text: String)

/**
 * An open EPUB.
 *
 * Chapters are the spine, in spine order, and nothing is read until it is asked for: the document
 * holds the package, the TOC and the chapter paths, not the book. The one piece of state is a
 * single-chapter cache, which exists because the reader asks for a chapter's HTML and its text in
 * immediate succession — caching both keeps the pair consistent and spares a second trip through the
 * archive. It holds one chapter, not the book, so a 700-chapter novel still costs one chapter of
 * memory while reading.
 *
 * Reads after [close] return empty results rather than throwing: closing happens when the reader
 * leaves the screen, and a chapter load that was already in flight should not become a crash.
 */
class EpubDocument internal constructor(
    private val archive: EpubArchive,
    private val opfDir: String,
    /** Archive path of each chapter, in reading order. */
    private val chapterPaths: List<String>,
    private val chapterTitles: Map<Int, String>,
    override val metadata: DocumentMetadata,
    override val outline: List<TocEntry>,
) : ReflowableDocument {

    override val format: BookFormat = BookFormat.EPUB

    override val capabilities: EngineCapabilities = EngineCapabilities(
        canSearch = true,
        canExtractText = true,
        // Nothing here lays out pages: the reader reflows the sanitised HTML at the user's font size.
        canRenderPages = false,
        hasOutline = outline.isNotEmpty(),
    )

    override val chapterCount: Int get() = chapterPaths.size

    @Volatile
    private var closed = false

    @Volatile
    private var cachedChapter: LoadedChapter? = null

    override fun chapter(index: Int): Chapter {
        checkIndex(index)
        return Chapter(
            index = index,
            title = chapterTitles[index],
            locator = ReadingLocator.Reflowable(index, charOffset = 0),
        )
    }

    override suspend fun chapterHtml(index: Int): String =
        withContext(Dispatchers.IO) { loadChapter(index)?.html.orEmpty() }

    override suspend fun chapterText(index: Int): String =
        withContext(Dispatchers.IO) { loadChapter(index)?.text.orEmpty() }

    /**
     * Reads a resource by the path a chapter's markup names.
     *
     * Paths are taken relative to the package document, which is what [chapterHtml] writes into
     * every rewritten `src`; a `/`-prefixed path means the archive root, and a bare one is also
     * accepted root-relative, because a caller assembling a path by hand tends to produce that form.
     * A path whose `..` segments would climb out of the archive resolves to nothing rather than to
     * whatever happens to sit above it.
     */
    override suspend fun resource(path: String): ByteArray? = withContext(Dispatchers.IO) {
        if (closed) return@withContext null
        val decoded = EpubPaths.decodePercent(EpubPaths.pathOf(path).trim())
        if (decoded.isEmpty()) return@withContext null
        val candidates = if (decoded.startsWith('/')) {
            listOfNotNull(EpubPaths.normalise(decoded.substring(1)))
        } else {
            listOfNotNull(EpubPaths.resolve(opfDir, decoded), EpubPaths.normalise(decoded))
        }
        for (candidate in candidates.distinct()) {
            archive.findEntry(candidate)?.let { return@withContext archive.readEntry(it) }
        }
        null
    }

    /**
     * Finds [query] in the chapters' text, in reading order.
     *
     * Chapters are read one at a time and each read re-opens the archive, so peak memory stays at
     * one chapter's text however long the book is — the alternative, holding every chapter's text at
     * once, would make searching a novel cost more memory than displaying it.
     *
     * Offsets are into the chapter's plain text, the same text [chapterText] returns, so a hit can be
     * turned back into a [ReadingLocator.Reflowable] without any conversion.
     */
    override suspend fun search(query: String, limit: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        // An empty query matches at every offset, which is a loop that never advances.
        if (closed || query.isEmpty() || limit <= 0) return@withContext emptyList()
        val hits = ArrayList<SearchHit>()
        for (index in chapterPaths.indices) {
            val text = loadChapter(index)?.text ?: continue
            var from = 0
            while (hits.size < limit) {
                val match = text.indexOf(query, from, ignoreCase = true)
                if (match < 0) break
                hits.add(searchHit(index, text, match, query.length))
                from = match + query.length
            }
            if (hits.size >= limit) break
        }
        hits
    }

    override fun close() {
        // Idempotent by construction: there is no handle to release, only a flag and the cache. The
        // archive is opened fresh for every read, so nothing is left open between calls.
        closed = true
        cachedChapter = null
    }

    private fun searchHit(chapterIndex: Int, text: String, matchIndex: Int, matchLength: Int): SearchHit {
        // Centre the window on the match without letting it drift past either end of the text, and
        // never start it after the match: a query longer than the window then simply makes the
        // snippet longer, which still satisfies "roughly this much context".
        val idealStart = matchIndex - (SNIPPET_LENGTH - matchLength) / 2
        val start = idealStart
            .coerceIn(0, maxOf(0, text.length - SNIPPET_LENGTH))
            .coerceAtMost(matchIndex)
        val end = minOf(text.length, maxOf(start + SNIPPET_LENGTH, matchIndex + matchLength))
        return SearchHit(
            locator = ReadingLocator.Reflowable(chapterIndex, charOffset = matchIndex),
            // The chapter's TOC title, or its number for a book with no usable outline.
            label = chapterTitles[chapterIndex] ?: (chapterIndex + 1).toString(),
            snippet = text.substring(start, end),
            matchStart = matchIndex - start,
            matchEnd = matchIndex - start + matchLength,
        )
    }

    private fun loadChapter(index: Int): LoadedChapter? {
        if (closed) return null
        checkIndex(index)
        cachedChapter?.let { if (it.index == index) return it }
        val archivePath = chapterPaths[index]
        val bytes = archive.readEntry(archivePath) ?: return null
        val sanitised = EpubChapterSanitiser.sanitise(bytes) { href ->
            EpubPaths.resolve(EpubPaths.directoryOf(archivePath), href)
                ?.let { EpubPaths.resourcePathFor(it, opfDir) }
        }
        val loaded = LoadedChapter(index, sanitised.html, sanitised.text)
        if (!closed) cachedChapter = loaded
        return loaded
    }

    private fun checkIndex(index: Int) {
        require(index in chapterPaths.indices) {
            "Chapter $index is outside 0..${chapterPaths.lastIndex}"
        }
    }

    private companion object {
        /** Context shown around a search hit. Wide enough to recognise, narrow enough for a list row. */
        const val SNIPPET_LENGTH = 80
    }
}
