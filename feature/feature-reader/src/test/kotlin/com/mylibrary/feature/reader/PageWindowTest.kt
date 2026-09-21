package com.mylibrary.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapters a paged reader holds at once, and the flat page list they flatten into.
 *
 * This is the arithmetic behind a page turn that crosses from one chapter into the next, and it is
 * the part of that feature which can be got wrong without anything on screen saying so: a window
 * that slides when it should not renumbers every page under the reader, and a page list that leaves
 * out a chapter turns its seam back into the dead end the window exists to remove. Both are decided
 * here, in functions that need no pager, no text measurer and no device.
 */
class PageWindowTest {

    // region Which chapters are held

    @Test
    fun `the window holds the chapter being read and the ones beside it`() {
        assertEquals(listOf(1, 2, 3), windowChapters(currentUnit = 2, totalUnits = 5))
    }

    @Test
    fun `the window stops at the ends of the document`() {
        assertEquals(listOf(0, 1), windowChapters(currentUnit = 0, totalUnits = 5))
        assertEquals(listOf(3, 4), windowChapters(currentUnit = 4, totalUnits = 5))
        assertEquals(listOf(0), windowChapters(currentUnit = 0, totalUnits = 1))
    }

    /**
     * A chapter after the last one is a chapter with no pages, and the pager would be given a page
     * count that includes them — a page that draws nothing, at the end of the book.
     */
    @Test
    fun `a chapter outside the document is never in the window`() {
        for (currentUnit in 0 until 5) {
            assertTrue(
                "unit $currentUnit: ${windowChapters(currentUnit, 5)}",
                windowChapters(currentUnit, 5).all { it in 0 until 5 },
            )
        }
    }

    @Test
    fun `a document with no chapters holds nothing`() {
        assertTrue(windowChapters(currentUnit = 0, totalUnits = 0).isEmpty())
    }

    /**
     * The whole point of the window: whatever page the reader is on, the page on either side of it
     * is in the list. Without that, the last page of a chapter has nowhere to turn to and has to be
     * a button again.
     */
    @Test
    fun `a single-page chapter has a page on both sides of it`() {
        // Chapter 1 is one page, so a turn either way leaves the chapter entirely.
        val window = windowChapters(currentUnit = 1, totalUnits = 3)

        assertEquals(listOf(0, 1, 2), window)
        assertTrue("the page before the chapter must be in the window", 0 in window)
        assertTrue("the page after it must be in the window", 2 in window)
    }

    // endregion

    // region How they flatten into pages

    @Test
    fun `the pages of the window run chapter by chapter, in order`() {
        val pages = windowPages(
            chapters = listOf(1, 2, 3),
            pageCounts = mapOf(1 to 2, 2 to 1, 3 to 3),
        )

        assertEquals(
            listOf(
                PageRef(1, 0), PageRef(1, 1),
                PageRef(2, 0),
                PageRef(3, 0), PageRef(3, 1), PageRef(3, 2),
            ),
            pages,
        )
    }

    @Test
    fun `a chapter that paginated to nothing contributes no pages`() {
        val pages = windowPages(
            chapters = listOf(0, 1, 2),
            pageCounts = mapOf(0 to 3, 1 to 0, 2 to 2),
        )

        assertEquals(listOf(PageRef(0, 0), PageRef(0, 1), PageRef(0, 2), PageRef(2, 0), PageRef(2, 1)), pages)
        assertTrue("a chapter with no pages must not appear at all", pages.none { it.chapterIndex == 1 })
    }

    @Test
    fun `a chapter still being laid out contributes no pages rather than failing`() {
        val pages = windowPages(chapters = listOf(0, 1), pageCounts = emptyMap())

        assertTrue(pages.isEmpty())
    }

    // endregion

    // region The key the pager is given

    /**
     * The key the pager is given: an entry's own identity, which names its book as well as its page.
     *
     * A key that collides gives two entries one entry's saved state, and the pager now holds two
     * books at once — so "chapter 1, page 0" is no longer an identity on its own, and the key is
     * what the reader's place is kept by when a handoff renumbers everything under it.
     */
    @Test
    fun `the key of a page is that page and no other`() {
        val pages = windowPages(
            chapters = listOf(0, 1, 2),
            pageCounts = mapOf(0 to 40, 1 to 1, 2 to 3),
        )

        val keys = pages.map { ref ->
            ReadingEntry.TextPage(bookId = 1, ref.chapterIndex, ref.pageIndexInChapter).readingKey()
        }

        assertEquals("no two pages may share a key", pages.size, keys.toSet().size)

        // The next volume's page 0 and the open volume's page 0 are both in the pager, and the reader
        // crosses from one to the other.
        assertTrue(
            ReadingEntry.TextPage(1, 0, 0).readingKey() != ReadingEntry.TextPage(2, 0, 0).readingKey(),
        )
    }

    // endregion

    // region Which page a remembered position lands on

    /** The three blocks of a chapter — at 0, 20 and 60 characters of its text — and nothing after. */
    private val offsets = ChapterTextMap(blockStarts = listOf(0, 20, 60), chapterLength = 100)

    /** A page beginning where [blockIndex] begins. */
    private fun pageAtBlock(blockIndex: Int) =
        ReaderPage(listOf(BlockSlice(blockIndex, start = 0, end = 10)))

    @Test
    fun `a page's offset is where its first slice begins in the chapter text`() {
        assertEquals(0, pageOffset(pageAtBlock(0), offsets))
        assertEquals(20, pageOffset(pageAtBlock(1), offsets))
        // Two thirds of the way into the second block, which begins at 20.
        assertEquals(35, pageOffset(ReaderPage(listOf(BlockSlice(1, 15, 30))), offsets))
    }

    @Test
    fun `a position lands on the page that holds it`() {
        val pages = listOf(pageAtBlock(0), pageAtBlock(1), pageAtBlock(2))

        // 25 is inside the second page — the one that begins at 20 and ends where the third begins.
        assertEquals(1, landingPageIn(pages, offsets, anchorOffset = 25, anchorBlock = null))
        assertEquals(2, landingPageIn(pages, offsets, anchorOffset = 60, anchorBlock = null))
    }

    /**
     * The offset a book was reopened with can be past the end of the chapter once the font has
     * changed and the chapter has fewer pages.
     */
    @Test
    fun `a position past the last page lands on the last page`() {
        val pages = listOf(pageAtBlock(0), pageAtBlock(1))

        assertEquals(1, landingPageIn(pages, offsets, anchorOffset = 9999, anchorBlock = null))
    }

    /**
     * A followed link names the block it targets, and the page holding it wins even when the offset
     * says otherwise: the block is where the link promised to go, and the offset is where the reader
     * happened to be.
     */
    @Test
    fun `a link's block wins over the offset`() {
        val pages = listOf(pageAtBlock(0), pageAtBlock(1), pageAtBlock(2))

        assertEquals(2, landingPageIn(pages, offsets, anchorOffset = 0, anchorBlock = 2))
    }

    /** A link into a chapter that does not contain its block still has to go somewhere. */
    @Test
    fun `a block the chapter does not have falls back to the offset`() {
        val pages = listOf(pageAtBlock(0), pageAtBlock(1))

        assertEquals(1, landingPageIn(pages, offsets, anchorOffset = 25, anchorBlock = 7))
    }

    @Test
    fun `a chapter with no pages has nowhere to land`() {
        assertEquals(-1, landingPageIn(emptyList(), offsets, anchorOffset = 0, anchorBlock = null))
    }

    // endregion
}
