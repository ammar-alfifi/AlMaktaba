package com.mylibrary.core.domain.engine

import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Chapter
import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.core.domain.model.EmbeddedFont
import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.PageImage
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.model.TocEntry

/**
 * A decoder for one family of formats.
 *
 * One engine instance is stateless and shared; every call to [open] returns an independent
 * [OpenDocument]. That is what lets the library screen open a book to read its cover while the
 * reader has a different book open.
 */
interface DocumentEngine {

    /** True when this engine can decode [format]. */
    fun supports(format: BookFormat): Boolean

    /**
     * Decodes [source].
     *
     * @param password supplied for encrypted PDFs and password-protected RAR archives.
     * @return [com.mylibrary.core.common.AppError.PasswordRequired] when the document is encrypted
     *   and [password] is absent or wrong, so the reader can prompt without parsing messages.
     */
    suspend fun open(source: DocumentSource, password: String? = null): AppResult<OpenDocument>
}

/**
 * An opened document. Holds native resources (file handles, a pdfium document), so the caller must
 * [close] it — the reader does this when leaving the screen and on process death.
 */
interface OpenDocument {
    val format: BookFormat
    val metadata: DocumentMetadata
    val capabilities: EngineCapabilities
    val outline: List<TocEntry>

    /** Finds [query] in the document's text, newest-first page order. */
    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<SearchHit>

    /**
     * The raw bytes of the document's own cover image, or `null` when it does not declare one.
     *
     * Defaults to `null` so that formats which have no notion of a cover — a comic archive, a plain
     * text file — need not implement it. EPUB overrides it, because an EPUB's declared cover is
     * worth using verbatim: it is the publisher's artwork rather than a rendering of page one.
     */
    suspend fun coverImage(): ByteArray? = null

    fun close()

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 200
    }
}

/** A document with fixed pages: PDF, CBZ, CBR. */
interface PagedDocument : OpenDocument {

    /** Total pages. Valid immediately after [DocumentEngine.open]. */
    val pageCount: Int

    /** The intrinsic size of a page, cheap enough to call while scrolling. */
    fun pageSize(pageIndex: Int): PageSize

    /**
     * Renders a page. Implementations are expected to be called off the main thread and to hold no
     * more than one page's pixels at a time.
     */
    suspend fun renderPage(request: PageRenderRequest): AppResult<PageImage>

    /**
     * The page's text layer, or `null` when the format has none.
     *
     * For CBZ/CBR this is always `null` — a comic page is an image with no text layer, and
     * pretending otherwise would make the reader offer a search that can never return a result.
     */
    suspend fun pageText(pageIndex: Int): String?
}

/**
 * Where a link inside a document points.
 *
 * [Internal] targets are resolved to a [com.mylibrary.core.domain.model.ReadingLocator] by the
 * engine, because only the engine knows how a fragment identifier maps onto a chapter's text. The
 * reader treats an internal target as a place to navigate to and keeps a back stack; it never has to
 * understand `href` syntax.
 */
sealed interface LinkTarget {
    /**
     * Somewhere else in the same document — a footnote, a cross-reference, or a TOC entry.
     *
     * [locator] identifies the chapter; [anchor] carries the fragment identifier when the link had
     * one. The split is deliberate: the engine owns the href grammar (relative paths,
     * percent-encoding, which spine item a path maps to) but has no idea where an anchor sits in
     * the rendered text, because it does not lay the chapter out. The reader parses the chapter,
     * finds the block carrying that id, and refines the offset. Neither side guesses at the
     * other's job.
     */
    data class Internal(
        val locator: com.mylibrary.core.domain.model.ReadingLocator,
        val anchor: String? = null,
    ) : LinkTarget

    /** Somewhere outside the document, handed to the platform to open. */
    data class External(val url: String) : LinkTarget
}

/** A document that re-flows: EPUB, TXT. */
interface ReflowableDocument : OpenDocument {

    /** Total chapters. Valid immediately after [DocumentEngine.open]. */
    val chapterCount: Int

    fun chapter(index: Int): Chapter

    /**
     * The chapter's content as a small, safe subset of HTML.
     *
     * EPUB chapters are XHTML that frequently contains scripts, inline styles that fight the
     * reader's typography, and layout tables. Implementations sanitise it down to paragraphs,
     * headings, emphasis and lists; TXT engines wrap plain text in the same shape so the reader has
     * exactly one code path.
     */
    suspend fun chapterHtml(index: Int): String

    /**
     * Resolves a resource referenced by a chapter, e.g. an image in an EPUB's `OEBPS/Images/`.
     *
     * @param path relative to the package document, as written in the chapter's markup.
     */
    suspend fun resource(path: String): ByteArray?

    /**
     * The plain text of a chapter, used for search and for computing reading progress.
     *
     * Separate from [chapterHtml] because searching markup would match tag names and entity
     * references, and because a character offset means different things in the two forms.
     */
    suspend fun chapterText(index: Int): String

    /**
     * Resolves an `href` found inside [chapterIndex] to a navigation target.
     *
     * This is what turns a footnote reference or a cross-reference into something tappable. The
     * engine — not the reader — owns the href grammar (relative paths, fragments, percent-encoding,
     * the difference between a same-chapter `#note1` and a web address), and it is also the only
     * layer that can reach the target chapter to find where the anchor actually is.
     *
     * @return `null` when the href is empty, malformed, or points outside the document.
     */
    suspend fun resolveLink(chapterIndex: Int, href: String): LinkTarget? = null

    /**
     * Fonts the document embeds. Empty for formats that cannot carry one.
     *
     * Defaulted to empty rather than declared abstract because only a container format can carry a
     * font: a TXT file has no place to put one, and a PDF draws its text with the fonts it already
     * contains rather than asking the reader for one. Those engines should not have to write a
     * method that says so.
     *
     * The bytes are read through [resource]; this call only reports what the document says it has,
     * so a caller can decide before paying for a font file what is worth loading.
     */
    suspend fun embeddedFonts(): List<EmbeddedFont> = emptyList()

    /**
     * The family the document's own body text asks for, or `null` when it does not ask.
     *
     * A publisher's choice of body face is not a user preference: it is what the book was typeset
     * in, and it is honoured the same way a chapter's italic or an image's placement is. It is
     * returned as written even when it names a font the document does not embed — `serif`, or a
     * family the reader's device happens to have — because only the caller knows what it can
     * actually render, and a name it cannot honour is still a better answer than the decoder
     * guessing at a fallback.
     */
    suspend fun defaultFontFamily(): String? = null
}
