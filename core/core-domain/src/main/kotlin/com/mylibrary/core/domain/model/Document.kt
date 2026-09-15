package com.mylibrary.core.domain.model

/** Bibliographic information a decoder can read out of a document. */
data class DocumentMetadata(
    val title: String? = null,
    val author: String? = null,
    /** BCP-47 tag when the document declares one, e.g. `ar` or `en`. */
    val language: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val identifier: String? = null,
)

/** One entry in a document's table of contents, nesting sub-sections. */
data class TocEntry(
    val title: String,
    val locator: ReadingLocator,
    val level: Int = 0,
    val children: List<TocEntry> = emptyList(),
)

/** One chapter of a reflowable document. */
data class Chapter(
    val index: Int,
    val title: String?,
    val locator: ReadingLocator,
)

/** A single search result inside a document. */
data class SearchHit(
    val locator: ReadingLocator,
    /** Where the hit is, e.g. `Page 12` or a chapter title. */
    val label: String?,
    /** Text around the match, with the matched run at [matchStart]..[matchEnd]. */
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
)

/** The intrinsic size of a page, in the document's own units. */
data class PageSize(val width: Int, val height: Int)

/**
 * A rendered page as straight ARGB_8888 pixels, row-major.
 *
 * Why raw pixels rather than an encoded image or a platform `Bitmap`: the domain layer is a plain
 * JVM module with no Android dependency, which is what makes the whole domain unit-testable without
 * Robolectric. Encoding to PNG and decoding again on every page turn would also cost far more than
 * the single array copy this costs, and the reader's byte-limited page cache measures pages in
 * exactly these bytes.
 */
class PageImage(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
) {
    /** Heap footprint of this page, used by the reader's byte-bounded cache. */
    val byteSize: Int get() = pixels.size * BYTES_PER_PIXEL

    init {
        require(pixels.size == width * height) {
            "Pixel buffer is ${pixels.size} but ${width}x${height} needs ${width * height}"
        }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

/**
 * A request to render one page at a particular size.
 *
 * [targetWidthPx] and [targetHeightPx] bound the allocation; the engine preserves the page's aspect
 * ratio inside that box. Callers pass the size of the viewport they actually need, so zooming to
 * 8x does not silently allocate an 8x bitmap of a 3000px page.
 */
data class PageRenderRequest(
    val pageIndex: Int,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    /** ARGB background painted behind pages with transparency. */
    val backgroundColorArgb: Int = DEFAULT_PAGE_BACKGROUND,
) {
    companion object {
        /** Opaque white, in ARGB. */
        const val DEFAULT_PAGE_BACKGROUND: Int = 0xFFFFFFFF.toInt()
    }
}

/** What a decoded document can do, so the reader can hide controls that would do nothing. */
data class EngineCapabilities(
    val canSearch: Boolean = false,
    val canExtractText: Boolean = false,
    val canRenderPages: Boolean = false,
    val hasOutline: Boolean = false,
    val requiresPassword: Boolean = false,
)
