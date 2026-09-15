package com.mylibrary.core.domain.engine

import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Chapter
import com.mylibrary.core.domain.model.DocumentMetadata
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
}
