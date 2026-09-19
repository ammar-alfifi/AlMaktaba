package com.mylibrary.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind a page number that counts the whole book.
 *
 * Two things are being pinned down, and neither shows itself on screen until a reader is deep in a
 * book. The first is that a number is only given when it is *true* — a chapter the measurement pass
 * has not reached must silence the count rather than contribute a zero, because a zero is a real
 * answer for a blank chapter and a book that is a few pages short would look right. The second is
 * that a count and a position are inverses: the number the bar shows must name a page that dragging
 * the bar to it can reach, and the offset that page is reached by is the one it was counted at.
 */
class BookPageIndexTest {

    /** A measured book: chapter 0 has two pages, chapter 1 one, chapter 2 three. */
    private fun measured() = BookPageIndex.of(
        listOf(
            intArrayOf(0, 400),
            intArrayOf(0),
            intArrayOf(0, 200, 500),
        ),
    )

    // region Counting

    @Test
    fun `the pages of every chapter are counted in the book`() {
        val index = measured()

        assertEquals(2, index.pageCountIn(0))
        assertEquals(1, index.pageCountIn(1))
        assertEquals(3, index.pageCountIn(2))
        assertEquals(6, index.totalPages)
        assertTrue(index.isComplete)
    }

    @Test
    fun `the pages before a chapter are the ones behind the reader once they are in it`() {
        val index = measured()

        assertEquals(0, index.pagesBefore(0))
        assertEquals(2, index.pagesBefore(1))
        assertEquals(3, index.pagesBefore(2))
    }

    /** A blank section is a real answer of zero, and the book must count straight through it. */
    @Test
    fun `a chapter that paginated to nothing still counts as measured`() {
        val index = BookPageIndex.of(listOf(intArrayOf(0), intArrayOf(), intArrayOf(0)))

        assertTrue(index.isComplete)
        assertEquals(2, index.totalPages)
        assertEquals(1, index.pagesBefore(2))
        assertEquals(2, index.pageNumber(chapter = 2, pageInChapter = 0))
    }

    // endregion

    // region Not knowing yet

    /**
     * The case the `null` entry exists for. A chapter the pass has not reached is not a chapter with
     * no pages, and giving it a count would make every page after it wrong by that count.
     */
    @Test
    fun `a chapter that has not been measured is not a chapter with no pages`() {
        val index = BookPageIndex.of(listOf(intArrayOf(0, 400), null, intArrayOf(0)))

        assertFalse(index.isComplete)
        assertNull(index.pageCountIn(1))
        assertNull("the book's length is unknown, not short", index.totalPages)
        assertNull(index.pagesBefore(2))
        assertNull(index.pageNumber(chapter = 1, pageInChapter = 0))
        assertNull(index.pageNumber(chapter = 2, pageInChapter = 0))
    }

    /**
     * What a page *number* waits on is the chapters behind it and nothing else. The pass runs from
     * the front of the book, so the reader's own number is known while the chapters ahead are not —
     * and that is the number worth showing early.
     */
    @Test
    fun `a page whose chapter and predecessors are measured has a number`() {
        val index = BookPageIndex.of(listOf(intArrayOf(0, 400), intArrayOf(0, 100), null, null))

        // Two pages behind chapter one, plus its second page — while the book's own length is still
        // unknown, because the chapters *ahead* of the number are not owed yet.
        assertEquals(4, index.pageNumber(chapter = 1, pageInChapter = 1))
        assertNull("but the book has no length yet", index.totalPages)
    }

    @Test
    fun `a chapter outside the book has no number`() {
        val index = measured()

        assertNull(index.pageCountIn(3))
        assertNull(index.pageNumber(chapter = 3, pageInChapter = 0))
        assertNull(index.pagesBefore(-1))
    }

    @Test
    fun `a book with no chapters counts nothing`() {
        val index = BookPageIndex.Empty

        assertEquals(0, index.chapterCount)
        assertEquals(0, index.totalPages)
        assertNull(index.pageNumber(chapter = 0, pageInChapter = 0))
        assertNull(index.locate(1))
    }

    // endregion

    // region Numbering

    @Test
    fun `a chapter's first page is numbered after the chapters behind it`() {
        val index = measured()

        assertEquals(1, index.pageNumber(chapter = 0, pageInChapter = 0))
        assertEquals(2, index.pageNumber(chapter = 0, pageInChapter = 1))
        // The seam: chapter one's only page is the book's third, with nothing restarting.
        assertEquals(3, index.pageNumber(chapter = 1, pageInChapter = 0))
        assertEquals(4, index.pageNumber(chapter = 2, pageInChapter = 0))
        assertEquals(6, index.pageNumber(chapter = 2, pageInChapter = 2))
    }

    /**
     * A remembered page can be past the end of a chapter once the font has changed and the chapter
     * has fewer pages. It rounds to the chapter's last page rather than to nothing — the same answer
     * [indexOfPage] gives, and for the same reason.
     */
    @Test
    fun `a page past the end of its chapter is the chapter's last page`() {
        val index = measured()

        assertEquals(2, index.pageNumber(chapter = 0, pageInChapter = 9))
        assertEquals(3, index.pageNumber(chapter = 1, pageInChapter = 9))
    }

    // endregion

    // region Getting back to the page

    /** Dragging the bar to a number has to arrive at the page that number names. */
    @Test
    fun `every number names a page and every page has that number`() {
        val index = measured()

        for (number in 1..index.totalPages!!) {
            val page = index.locate(number) ?: error("no page for $number")
            assertEquals(
                "$number must name the page that is numbered $number",
                number,
                index.pageNumber(page.chapterIndex, page.pageIndexInChapter),
            )
        }
    }

    /** The offset is the whole point of carrying the pages and not just their counts. */
    @Test
    fun `a located page carries the offset it begins at`() {
        val index = measured()

        assertEquals(
            BookPage(chapterIndex = 2, pageIndexInChapter = 1, charOffset = 200),
            index.locate(5),
        )
        assertEquals(BookPage(0, 0, 0), index.locate(1))
        assertEquals(BookPage(2, 2, 500), index.locate(6))
    }

    @Test
    fun `a number outside the book names no page`() {
        val index = measured()

        assertNull(index.locate(0))
        assertNull(index.locate(-3))
        assertNull(index.locate(7))
    }

    /** Somewhere in an unmeasured chapter is not somewhere the reader can be sent. */
    @Test
    fun `a number past a chapter that has not been measured names no page`() {
        val index = BookPageIndex.of(listOf(intArrayOf(0, 400), null))

        assertEquals(BookPage(0, 1, 400), index.locate(2))
        assertNull("the third page is in a chapter of unknown length", index.locate(3))
    }

    // endregion
}
