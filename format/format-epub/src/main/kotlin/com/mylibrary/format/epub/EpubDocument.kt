package com.mylibrary.format.epub

import com.mylibrary.core.domain.engine.LinkTarget
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Chapter
import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.core.domain.model.EmbeddedFont
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** One chapter, sanitised once so that [EpubDocument.chapterHtml] and [EpubDocument.chapterText] agree. */
private class LoadedChapter(val index: Int, val html: String, val text: String)

/**
 * An open EPUB.
 *
 * Chapters are the spine, in spine order, and nothing is read until it is asked for: the document
 * holds the package, the TOC and the chapter paths, not the book. What is kept is two results rather
 * than two copies of the book: a single-chapter cache, which exists because the reader asks for a
 * chapter's HTML and its text in immediate succession — caching both keeps the pair consistent and
 * spares a second trip through the archive — and the document's own typography, which is two facts
 * read out of its stylesheets and asked for once per book rather than once per chapter. So a
 * 700-chapter novel still costs one chapter of memory while reading.
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
    /** The CSS the document declares, read lazily the first time its typography is asked for. */
    private val stylesheets: StylesheetSources,
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

    /**
     * The document's typography, read once.
     *
     * `null` means "not read yet", so a document with no CSS — most books — is read for its absence
     * exactly once rather than on every chapter open. A loaded result of *empty* fonts and no family
     * is a real answer, which is why the two are distinguished by a wrapper rather than by the list.
     */
    @Volatile
    private var cachedStyles: DocumentStyles? = null

    /**
     * Maps an archive path back to the chapter it became, which is what turns a resolved href into a
     * chapter index.
     *
     * Built on first use, and only then: a book without links — most books — never pays for the two
     * maps, while the document still holds the only list they can be built from. The [ChapterIndex]
     * class is shared with the TOC reader rather than reimplemented, so an href that matches a
     * chapter's path case-insensitively does so here for the same reason it does there.
     */
    private val spineIndex: ChapterIndex by lazy { ChapterIndex(chapterPaths) }

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
     * The fonts the book carries, in the order its stylesheets declare them.
     *
     * Each one's [EmbeddedFont.path] is spelled for [resource], so the reader loads a face with the
     * same call it loads an image with and never has to know which file inside the archive declared
     * it. Faces whose file the archive does not actually contain are left out: a manifest promising a
     * font the zip never shipped is common enough that reporting it would be noise, and the reader
     * substitutes for a missing face exactly as it does for a book that has none.
     */
    override suspend fun embeddedFonts(): List<EmbeddedFont> =
        withContext(Dispatchers.IO) { loadStyles()?.fonts.orEmpty() }

    /**
     * The family the book's own body rule asks for, or `null` when its CSS does not say.
     *
     * Returned as written, whether or not [embeddedFonts] carries it: a book typeset in a family the
     * reader's device happens to have, or in a generic like `serif`, is stating a preference the
     * reader is better placed to judge than this decoder is.
     */
    override suspend fun defaultFontFamily(): String? =
        withContext(Dispatchers.IO) { loadStyles()?.bodyFontFamily }

    /**
     * Resolves an `href` written inside chapter [chapterIndex].
     *
     * The rules are applied in this order, and the order is half the behaviour: `#a:b` is a fragment
     * and not a scheme, and `Text/notes:1.xhtml` is a path and not a scheme, because a colon only
     * introduces one when everything before it is a scheme's alphabet.
     *
     *  - `#anchor` — a place in the chapter the link was written in, so there is no path to resolve at
     *    all. `#` alone means the top of that chapter, which is the same place with nothing to look
     *    for once the reader gets there.
     *  - Anything with a scheme (`http:`, `https:`, `mailto:`, `tel:`, and any other) is outside the
     *    archive by definition and is returned verbatim. Deciding whether a scheme is worth opening —
     *    or safe to open — belongs to the platform that opens it, and a URL rewritten here would only
     *    be a URL the platform has to undo.
     *  - Everything else is an archive path, resolved against *this chapter's* directory: an href in
     *    a chapter is relative to that chapter, never to the package document, and getting that wrong
     *    is the classic way a cross-reference lands in the wrong file.
     *
     * The locator's `charOffset` is deliberately `0`. This engine knows which chapter a fragment
     * belongs to, because it owns the href grammar and the spine, but it does not know where the
     * anchor sits in the chapter's rendered text — it never lays a chapter out. That half belongs to
     * the reader, which refines the position from [LinkTarget.Internal.anchor]; neither side guesses
     * at the other's job.
     *
     * @return `null` for a blank href, for one naming something that is not a chapter (a stylesheet,
     *   an image, a file the book never shipped), and for one whose `..` segments climb past the
     *   archive root. A link that cannot be followed is better than a jump to an arbitrary place.
     *   Nothing here throws: an href is book-supplied text, so every step answers `null` rather than
     *   failing, and a malformed link in a hostile book never reaches the reader as an exception.
     */
    override suspend fun resolveLink(chapterIndex: Int, href: String): LinkTarget? =
        withContext(Dispatchers.IO) {
            if (closed) return@withContext null
            val target = href.trim()
            if (target.isEmpty()) return@withContext null
            // Nothing can be resolved *from* a chapter this book does not have. A caller passing such
            // an index is a bug rather than a broken book, so it resolves to nothing instead of
            // raising the way `chapter()` does — a link that fails is not worth taking a screen down.
            if (chapterIndex !in chapterPaths.indices) return@withContext null

            if (target.startsWith("#")) return@withContext internalLink(chapterIndex, target)
            if (SCHEME.containsMatchIn(target)) return@withContext LinkTarget.External(target)

            val chapterDir = EpubPaths.directoryOf(chapterPaths[chapterIndex])
            val archivePath = EpubPaths.resolve(chapterDir, target) ?: return@withContext null
            val targetChapter = spineIndex.indexOf(archivePath) ?: return@withContext null
            internalLink(targetChapter, target)
        }

    /**
     * A same-document link: the chapter, plus the fragment decoded.
     *
     * A fragment is a document identifier rather than a path, so it is percent-encoded in the href
     * and has to come back as text before it can be matched against an element's `id`. The path part
     * is not decoded here — [EpubPaths.resolve] already did that, before normalising, which is the
     * order that keeps an encoded `..` from slipping past the traversal guard.
     */
    private fun internalLink(chapterIndex: Int, href: String): LinkTarget.Internal {
        val anchor = EpubPaths.fragmentOf(href)
            ?.let { EpubPaths.decodePercent(it) }
            ?.takeIf { it.isNotEmpty() }
        return LinkTarget.Internal(
            locator = ReadingLocator.Reflowable(chapterIndex, charOffset = 0),
            anchor = anchor,
        )
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
        // Idempotent by construction: there is no handle to release, only a flag and the caches. The
        // archive is opened fresh for every read, so nothing is left open between calls.
        closed = true
        cachedChapter = null
        cachedStyles = null
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

    /**
     * Reads the document's stylesheets once, or answers `null` when there is nothing to read from.
     *
     * Every failure inside [EpubStyles] is already a missing rule rather than an exception; what is
     * caught here is the archive itself failing halfway through a read, which is a book that cannot
     * be read from any more rather than a stylesheet that is wrong. Neither is worth taking a screen
     * down for, so the answer is "no fonts" — the reader's own font is a perfectly good book.
     */
    private fun loadStyles(): DocumentStyles? {
        if (closed) return null
        cachedStyles?.let { return it }
        val styles = try {
            EpubStyles.read(archive, stylesheets, opfDir)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            DocumentStyles(fonts = emptyList(), bodyFontFamily = null)
        }
        if (!closed) cachedStyles = styles
        return styles
    }

    private fun checkIndex(index: Int) {
        require(index in chapterPaths.indices) {
            "Chapter $index is outside 0..${chapterPaths.lastIndex}"
        }
    }

    private companion object {
        /** Context shown around a search hit. Wide enough to recognise, narrow enough for a list row. */
        const val SNIPPET_LENGTH = 80

        /**
         * A URI scheme prefix, per RFC 3986: `http:`, `mailto:`, `tel:`, `urn:`.
         *
         * The colon only introduces a scheme when everything before it is a scheme's alphabet, which
         * is exactly what keeps `../Text/chapter:one.xhtml` a path — `/` is not in that alphabet. The
         * format's own answer for a genuinely relative first segment containing a colon is to write
         * it `./chapter:one.xhtml`, which no longer matches here.
         */
        val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    }
}
