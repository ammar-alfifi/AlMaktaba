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

    /**
     * A compact encoding for carrying a locator as a navigation argument.
     *
     * `p` pages and `r` chapters, in the shape `p12` or `r3:450` — chapter 3, offset 450. Deliberately
     * not JSON: the route string is visible in the back stack and in logs, and one short token is
     * easier to read and impossible to get out of step with the parser. [Companion.parse] is its
     * inverse, returning `null` for anything malformed so a stale or hand-edited argument degrades to
     * the saved reading position rather than crashing the reader.
     */
    fun encoded(): String = when (this) {
        is Paged -> "p$pageIndex"
        is Reflowable -> "r$chapterIndex:$charOffset"
    }

    companion object {

        /** The start of any document. */
        val start: ReadingLocator = Paged(0)

        /** Reverses [encoded]; `null` when the string is not a locator this app wrote. */
        fun parse(encoded: String): ReadingLocator? {
            val body = encoded.drop(1)
            return when (encoded.firstOrNull()) {
                'p' -> body.toIntOrNull()
                    ?.takeIf { it >= 0 }
                    ?.let { Paged(it) }

                'r' -> body.split(':')
                    .takeIf { it.size == 2 }
                    ?.let { parts ->
                        val chapter = parts[0].toIntOrNull() ?: return null
                        val offset = parts[1].toIntOrNull() ?: return null
                        if (chapter < 0 || offset < 0) return null
                        Reflowable(chapter, offset)
                    }

                else -> null
            }
        }
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
