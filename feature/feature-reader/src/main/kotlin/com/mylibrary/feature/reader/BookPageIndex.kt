package com.mylibrary.feature.reader

import androidx.compose.runtime.Immutable

/**
 * One page's place in a whole reflowable book.
 *
 * [charOffset] is carried because a page *number* is not somewhere the reader can be sent. The
 * pagination a number came from is thrown away the moment the text is laid out again, and what
 * survives — what a reading position is kept as — is a character offset into the chapter's own text.
 * So the number names a page and the offset is how the reader gets to it; [PageWindow.landingPageIn]
 * is the reverse of this, and the two are the only reason a page can be both counted and reached.
 */
data class BookPage(
    val chapterIndex: Int,
    val pageIndexInChapter: Int,
    val charOffset: Int,
)

/**
 * How many pages every chapter of a book came to, and where each of them begins.
 *
 * The reader used to hold one chapter at a time and count within it, so the end of a chapter reset
 * the counter and the progress bar restarted: a book read as a run of separate chapters rather than
 * as a book. Counting the book needs a number nothing else knows — how long each chapter is *in
 * pages* — and the only way to know that is to lay the text out, which is why this is filled in by a
 * measurement pass over the whole document ([ReflowablePagedReader]) rather than looked up.
 *
 * A chapter that has not been measured is `null` rather than zero, and the distinction is the whole
 * reason the list is not an `IntArray`. A section holding only whitespace, or an EPUB spine item with
 * no text, genuinely paginates to no pages — and a book whose second chapter is empty must still be
 * countable — so "measured, and the answer is none" has to be tellable from "not measured yet".
 * A count that cannot be given honestly is `null` out of every reader below, and the caller falls
 * back to counting chapters; a count that is there is never a guess.
 *
 * The index is a pure value and is replaced whole rather than edited, because it is rebuilt from
 * nothing whenever the text is laid out differently — a font size, a margin, a rotation. Nothing
 * here knows about pagination except the numbers it is handed; the arithmetic is decided in this
 * file and tested without a device, the same way [windowChapters] and [paginate] are.
 */
@Immutable
class BookPageIndex private constructor(
    /** Per chapter, the character offset each of its pages begins at, or `null` if unmeasured. */
    private val chapterPages: List<IntArray?>,
) {

    val chapterCount: Int get() = chapterPages.size

    /** How many pages [chapter] came to, or `null` while it is unmeasured. */
    fun pageCountIn(chapter: Int): Int? = chapterPages.getOrNull(chapter)?.size

    /**
     * How many pages the book holds before [chapter] — that is, how many the reader has gone past
     * once they are in it — or `null` while any chapter before it is unmeasured.
     *
     * A prefix rather than the whole book, so that a page number can be known before the pass has
     * finished the chapters ahead of the reader: only what lies *behind* the number has to be
     * measured for it to be true.
     */
    fun pagesBefore(chapter: Int): Int? {
        if (chapter < 0) return null
        var total = 0
        for (index in 0 until chapter) {
            val pages = chapterPages.getOrNull(index) ?: return null
            total += pages.size
        }
        return total
    }

    /** How many pages the whole book holds, or `null` until every chapter has been measured. */
    val totalPages: Int? get() = pagesBefore(chapterCount)

    /** True once every chapter has been measured, which is what a book-wide total waits on. */
    val isComplete: Boolean get() = chapterPages.all { it != null }

    /**
     * The page's number in the book, counting from one — what the reader is shown — or `null` while
     * the chapters behind it are unmeasured, or while the chapter holding it is.
     *
     * A [pageInChapter] past the end of its chapter answers with the chapter's last page rather than
     * with nothing, which is the same rounding [indexOfPage] does and for the same reason: the caller
     * is usually a remembered position and the chapter may have fewer pages than it did.
     */
    fun pageNumber(chapter: Int, pageInChapter: Int): Int? {
        val before = pagesBefore(chapter) ?: return null
        val count = pageCountIn(chapter) ?: return null
        if (count == 0) return null
        return before + pageInChapter.coerceIn(0, count - 1) + 1
    }

    /**
     * The page the book-wide number [pageNumber] names, counting from one — what dragging the
     * progress bar across a book asks for — or `null` when the number is outside the book or the
     * chapters before it are unmeasured.
     */
    fun locate(pageNumber: Int): BookPage? {
        if (pageNumber < 1) return null
        var remaining = pageNumber
        chapterPages.forEachIndexed { chapter, pages ->
            pages ?: return null
            if (remaining <= pages.size) {
                val page = remaining - 1
                return BookPage(chapter, page, pages[page])
            }
            remaining -= pages.size
        }
        return null
    }

    companion object {

        /** The index a measurement pass has produced: one entry per chapter, in reading order. */
        fun of(chapterPages: List<IntArray?>): BookPageIndex = BookPageIndex(chapterPages.toList())

        /** No chapters at all — a book that has not opened, or has none. */
        val Empty: BookPageIndex = BookPageIndex(emptyList())
    }
}
