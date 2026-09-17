package com.mylibrary.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the progress the reader reports.
 *
 * The rule being pinned down is agreement, not arithmetic: the library card and the bookmarks list
 * read their percentage from `ReadingProgressUseCase`, so a reader that computed its own would be
 * able to say 40% about a book the shelf says is 12% through. The two differ by one unit between
 * the kinds — a page index is a position arrived at, a chapter index is one just entered — and that
 * asymmetry is easy to "fix" into a disagreement, which is what these catch.
 */
class ReaderProgressTest {

    private fun paged(current: Int, total: Int) =
        ReaderUiState(isPageImages = true, currentUnit = current, totalUnits = total).progress

    private fun reflowable(current: Int, total: Int) =
        ReaderUiState(isPageImages = false, currentUnit = current, totalUnits = total).progress

    @Test
    fun `a paged document counts the page being read as finished`() {
        assertEquals(0.1f, paged(current = 0, total = 10), 0.0001f)
        assertEquals(0.5f, paged(current = 4, total = 10), 0.0001f)
        assertEquals(1.0f, paged(current = 9, total = 10), 0.0001f)
    }

    @Test
    fun `a reflowable document counts the chapters behind the reader`() {
        // Entering chapter one means chapter zero is read, not chapter one.
        assertEquals(0f, reflowable(current = 0, total = 10), 0.0001f)
        assertEquals(0.4f, reflowable(current = 4, total = 10), 0.0001f)
        assertEquals(0.9f, reflowable(current = 9, total = 10), 0.0001f)
    }

    @Test
    fun `a document with no units reports no progress rather than dividing by zero`() {
        assertEquals(0f, paged(current = 0, total = 0), 0.0001f)
        assertEquals(0f, reflowable(current = 0, total = 0), 0.0001f)
    }

    /** A state that is mid-open — a book set but no document yet — must not report progress. */
    @Test
    fun `a document that has not opened yet reports no progress`() {
        assertEquals(0f, ReaderUiState().progress, 0.0001f)
    }
}
