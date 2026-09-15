package com.mylibrary.core.domain.model

/**
 * Where the reader is inside a document.
 *
 * The two variants exist because paged and reflowable documents fail differently. A page index
 * survives a font-size change (pages are fixed) but a character offset does not. Conversely, a
 * character offset survives re-flowing but means nothing in a PDF. Modelling both explicitly means
 * the reader can restore the exact place without ever guessing.
 */
sealed interface ReadingLocator {

    /** A fixed page in a paged document. Zero-based. */
    data class Paged(val pageIndex: Int) : ReadingLocator

    /**
     * A character offset inside a chapter of a reflowable document.
     *
     * [charOffset] is the index of the anchor character within the chapter's *plain text*, not its
     * markup, so it stays valid no matter how the chapter is rendered.
     */
    data class Reflowable(val chapterIndex: Int, val charOffset: Int) : ReadingLocator

    companion object {
        val start: ReadingLocator = Paged(0)
    }
}

/** A saved reading position for one book. */
data class ReadingPosition(
    val bookId: Long,
    val locator: ReadingLocator,
    /** How far through the book this position is, in the range 0f..1f. */
    val percent: Float,
    val updatedAt: Long = System.currentTimeMillis(),
    /** A short quote from the current position, shown on the library card. */
    val excerpt: String? = null,
)

/**
 * A bookmark the user placed, optionally carrying a note and a colour.
 *
 * Highlights are the same thing with a colour and a non-empty [note]; keeping them one type avoids
 * two parallel tables, two sets of DAO methods and two UI lists that behave identically.
 */
data class Bookmark(
    val id: Long = NO_ID,
    val bookId: Long,
    val locator: ReadingLocator,
    /** A human-readable location label such as a page number or chapter title. */
    val label: String? = null,
    /** The text at the bookmarked position, so the list is recognisable at a glance. */
    val excerpt: String? = null,
    val note: String? = null,
    /** ARGB colour for a highlight; `null` for a plain bookmark. */
    val colorArgb: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** True when this bookmark is really a highlight. */
    val isHighlight: Boolean get() = colorArgb != null

    companion object {
        const val NO_ID: Long = 0L
    }
}
