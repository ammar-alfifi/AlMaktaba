package com.mylibrary.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for the comparison the scrolling comic column makes against the reader's state.
 *
 * The bug these pin down is the one a reader hit moving between two consecutive volumes: the state
 * had crossed into the next book, the column was still resting on the last page of the one just
 * finished, and the comparison read that page as "the open book's nearest edge" — which happened to
 * equal the arrival page (the next volume's first), so the column was never moved. The page on
 * screen and the progress bar described two different books from then on.
 */
class PagedScrollReaderTest {

    private val openBook = 2L
    private val neighbour = 1L

    @Test
    fun `a page of the open book reports its own number`() {
        assertEquals(
            3,
            columnReadingPage(
                openBookId = openBook,
                totalUnits = 10,
                targetIndex = 5,
                onScreenIndex = 5,
                visible = ReadingEntry.Page(openBook, 3),
            ),
        )
    }

    @Test
    fun `a page of a neighbouring volume is never the open book's position`() {
        // The handover case: the column shows a page of the volume just left, and no page number of
        // the open book may be inferred from it — so it must differ from any real current unit.
        val reading = columnReadingPage(
            openBookId = openBook,
            totalUnits = 10,
            targetIndex = 12,
            onScreenIndex = 9,
            visible = ReadingEntry.Page(neighbour, 9),
        )

        assertEquals(NOT_A_PAGE, reading)
        assertNotEquals("it must not be mistaken for a real page", 0, reading)
    }

    @Test
    fun `a seam before the open book reads as its first page`() {
        assertEquals(
            0,
            columnReadingPage(
                openBookId = openBook,
                totalUnits = 10,
                targetIndex = 4,
                onScreenIndex = 3,
                visible = null,
            ),
        )
    }

    @Test
    fun `a seam after the open book reads as its last page`() {
        assertEquals(
            9,
            columnReadingPage(
                openBookId = openBook,
                totalUnits = 10,
                targetIndex = 9,
                onScreenIndex = 10,
                visible = null,
            ),
        )
    }
}
