package com.mylibrary.feature.reader

import com.mylibrary.core.domain.model.ProgressScope
import com.mylibrary.core.domain.model.ReaderLayout
import com.mylibrary.core.domain.model.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // Chapters of 2, 3 and 1 pages; the offsets' values are irrelevant to the progress arithmetic,
    // only the counts are. One is null in the incomplete case below — measured-to-nothing and
    // not-measured stay distinct, which is the whole reason they are `null` rather than zeros.
    private val pages = listOf(intArrayOf(0, 40), intArrayOf(0, 30, 70), intArrayOf(0))

    /** A paged reflowable book whose index exists, sitting on chapter two's second page. */
    private fun book(scope: ProgressScope, index: BookPageIndex? = BookPageIndex.of(pages)) =
        ReaderUiState(
            isPageImages = false,
            currentUnit = 1,
            totalUnits = 3,
            reflowPage = 1,
            reflowPageCount = 3,
            bookIndex = index,
            settings = ReaderSettings(layout = ReaderLayout.PAGED, progressScope = scope),
        )

    @Test
    fun `a measured book in book scope counts its pages across the chapters`() {
        val state = book(ProgressScope.BOOK)
        // Two pages behind chapter two, plus the second page of it, one-based.
        assertEquals(4, state.bookPageNumber)
        assertEquals(6, state.bookPageCount)
        assertEquals(4f / 6f, state.progress, 0.0001f)
    }

    @Test
    fun `chapter scope counts chapters even when the book has been measured`() {
        val state = book(ProgressScope.CHAPTER)
        assertNull("the setting asked for chapters, so no book-wide numbers are offered", state.bookPageNumber)
        assertNull(state.bookPageCount)
        // Exactly the value the property had before a book could be measured at all.
        assertEquals((1 + 1f / 3f) / 3f, state.progress, 0.0001f)
    }

    @Test
    fun `an incomplete index falls back to the chapter answer while it waits`() {
        val incomplete = BookPageIndex.of(listOf(pages[0], null, pages[2]))
        val state = book(ProgressScope.BOOK, index = incomplete)
        // A number without the book's length behind it is a different sentence, and is not shown.
        assertNull(state.bookPageNumber)
        assertNull(state.bookPageCount)
        assertEquals((1 + 1f / 3f) / 3f, state.progress, 0.0001f)
    }

    @Test
    fun `no index at all reports the chapter answer`() {
        val state = book(ProgressScope.BOOK, index = null)
        assertNull(state.bookPageNumber)
        assertNull(state.bookPageCount)
        assertEquals((1 + 1f / 3f) / 3f, state.progress, 0.0001f)
    }
}
