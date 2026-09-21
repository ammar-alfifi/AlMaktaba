package com.mylibrary.feature.reader

import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.usecase.FolderSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading order across the volumes of a folder, and the identity of what is in it.
 *
 * This is the arithmetic behind a reader who scrolls or turns past the end of one file and carries on
 * into the next one, and it is the part of that feature nothing on screen would report as wrong: a
 * seam placed at the wrong end, a neighbour's page mistaken for the open book's, or a key that does
 * not survive the next volume becoming the open one all show up as a reader who has been moved
 * somewhere they did not ask to be. All of it is decided here, in functions that need no document, no
 * pager and no device.
 */
class ReadingSequenceTest {

    private fun book(id: Long, title: String, folderId: Long? = null) = Book(
        id = id,
        title = title,
        uri = "content://book/$id",
        format = BookFormat.CBZ,
        folderId = folderId,
    )

    private val first = book(1, "المجلد 1", folderId = 7)
    private val second = book(2, "المجلد 2", folderId = 7)
    private val third = book(3, "المجلد 3", folderId = 7)

    /** The open book's own pages, which is what every presentation starts from. */
    private fun pages(book: Book, count: Int): List<ReadingEntry> =
        List(count) { ReadingEntry.Page(book.id, it) }

    private fun state(
        open: Book = second,
        previous: Book? = first,
        next: Book? = third,
        totalUnits: Int = 3,
        currentUnit: Int = 0,
        previousSegment: ReaderSegment? = null,
        nextSegment: ReaderSegment? = null,
        unavailable: Set<Long> = emptySet(),
    ) = ReaderUiState(
        book = open,
        totalUnits = totalUnits,
        currentUnit = currentUnit,
        sequence = FolderSequence(
            book = open,
            previous = previous,
            next = next,
            folderName = "السلاسل",
        ),
        previousSegment = previousSegment,
        nextSegment = nextSegment,
        unavailableNeighbours = unavailable,
    )

    // region The order itself

    @Test
    fun `a book with no neighbours is its own units`() {
        val order = readingOrder(primaryBookId = second.id, primary = pages(second, 3))

        assertEquals(pages(second, 3), order)
    }

    @Test
    fun `the next volume follows the open one, behind a seam`() {
        val order = readingOrder(
            primaryBookId = second.id,
            primary = pages(second, 3),
            next = ReadingNeighbour(third.id, pages(third, 2)),
        )

        assertEquals(
            listOf(
                ReadingEntry.Page(second.id, 0),
                ReadingEntry.Page(second.id, 1),
                ReadingEntry.Page(second.id, 2),
                ReadingEntry.Seam(fromBookId = second.id, toBookId = third.id),
                ReadingEntry.Page(third.id, 0),
                ReadingEntry.Page(third.id, 1),
            ),
            order,
        )
    }

    @Test
    fun `the previous volume is drawn above the open one, behind its own seam`() {
        val order = readingOrder(
            primaryBookId = second.id,
            primary = pages(second, 2),
            previous = ReadingNeighbour(first.id, pages(first, 2)),
        )

        assertEquals(
            listOf(
                ReadingEntry.Page(first.id, 0),
                ReadingEntry.Page(first.id, 1),
                ReadingEntry.Seam(fromBookId = first.id, toBookId = second.id),
                ReadingEntry.Page(second.id, 0),
                ReadingEntry.Page(second.id, 1),
            ),
            order,
        )
    }

    @Test
    fun `a volume that cannot be opened still gets its seam`() {
        // The book exists in the folder, so the reader must be told what it is even though nothing
        // of it can be drawn — a password waiting to be typed, a file that will not open, a volume
        // this reader draws as text. A seam with nothing after it is a statement; no seam at all is
        // the dead end the whole feature exists to remove.
        val order = readingOrder(
            primaryBookId = second.id,
            primary = pages(second, 1),
            next = ReadingNeighbour(third.id, units = emptyList()),
        )

        assertEquals(2, order.size)
        assertEquals(ReadingEntry.Seam(fromBookId = second.id, toBookId = third.id), order.last())
    }

    // endregion

    // region Identity

    @Test
    fun `a key names a page of its own book`() {
        // Two books have a page 0, and the reader crosses from one to the other. If the two shared a
        // key the pager and the column would hand one page's saved state to the other, in the very
        // frame that the next volume becomes the open one.
        assertTrue(
            ReadingEntry.Page(second.id, 0).readingKey() != ReadingEntry.Page(third.id, 0).readingKey(),
        )
        assertEquals(
            ReadingEntry.Page(second.id, 4).readingKey(),
            ReadingEntry.Page(second.id, 4).readingKey(),
        )
    }

    @Test
    fun `a key separates the granularities that share a number`() {
        val chapter = ReadingEntry.Chapter(second.id, 1)
        val page = ReadingEntry.Page(second.id, 1)
        val textPage = ReadingEntry.TextPage(second.id, 1, 1)
        val seam = ReadingEntry.Seam(second.id, third.id)

        assertEquals(
            setOf(chapter.readingKey(), page.readingKey(), textPage.readingKey(), seam.readingKey()).size,
            4,
        )
    }

    @Test
    fun `the two seams of a folder are different entries`() {
        assertTrue(
            ReadingEntry.Seam(first.id, second.id).readingKey() !=
                ReadingEntry.Seam(second.id, third.id).readingKey(),
        )
    }

    // endregion

    // region Finding a position in the order

    @Test
    fun `a position is found in the book it belongs to`() {
        // Page 0 exists in all three books. What makes the lookup correct is the book, not the index.
        val order = readingOrder(
            primaryBookId = second.id,
            primary = pages(second, 3),
            previous = ReadingNeighbour(first.id, pages(first, 2)),
            next = ReadingNeighbour(third.id, pages(third, 2)),
        )

        assertTrue(order[order.indexOfPage(second.id, 0)] == ReadingEntry.Page(second.id, 0))
        assertTrue(order[order.indexOfPage(first.id, 0)] == ReadingEntry.Page(first.id, 0))
        assertTrue(order[order.indexOfPage(third.id, 0)] == ReadingEntry.Page(third.id, 0))
    }

    @Test
    fun `a position past the end of a book lands on its last page, not the next book's first`() {
        // What a remembered position looks like after the text has been laid out again: a page 12 in
        // a chapter that now has four. Rounding backwards is the whole point — the reader's place is
        // the nearest page that still exists, and continuing forwards would put them in the *next
        // volume*, which reads as the book having skipped itself.
        val order = readingOrder(
            primaryBookId = second.id,
            primary = pages(second, 3),
            next = ReadingNeighbour(third.id, pages(third, 2)),
        )

        assertEquals(2, order.indexOfPage(second.id, 11))
        assertEquals(ReadingEntry.Page(second.id, 2), order[2])
    }

    @Test
    fun `a book that is not in the order has no position in it`() {
        val order = readingOrder(primaryBookId = second.id, primary = pages(second, 2))

        assertEquals(-1, order.indexOfPage(third.id, 0))
        assertEquals(-1, order.indexOfChapter(third.id, 0))
        assertEquals(-1, order.indexOfTextPage(third.id, 0, 0))
    }

    @Test
    fun `the entry at an index is null past either end`() {
        val order = readingOrder(primaryBookId = second.id, primary = pages(second, 2))

        assertEquals(ReadingEntry.Page(second.id, 0), order.entryAt(0))
        assertNull(order.entryAt(2))
        assertNull(order.entryAt(-1))
    }

    // endregion

    // region Neighbours as the state reports them

    @Test
    fun `a neighbour that is open contributes its units`() {
        val state = state(
            nextSegment = ReaderSegment(third, unitCount = 2, isPageImages = true),
        )

        val next = state.nextNeighbour { id, count -> List(count) { ReadingEntry.Page(id, it) } }

        assertEquals(third.id, next?.bookId)
        assertEquals(2, next?.units?.size)
    }

    @Test
    fun `a neighbour that is not open is placed with no units`() {
        // Still being opened, or refused: the id is there so the seam can be placed and named, and
        // there is nothing to draw behind it yet.
        val state = state(nextSegment = null)

        val next = state.nextNeighbour { id, count -> List(count) { ReadingEntry.Page(id, it) } }

        assertEquals(third.id, next?.bookId)
        assertTrue(next?.units.orEmpty().isEmpty())
    }

    @Test
    fun `the ends of a folder have no neighbour on the outside`() {
        val onlyChild = state(previous = null, next = null)

        assertNull(onlyChild.previousNeighbour { id, count -> List(count) { ReadingEntry.Page(id, it) } })
        assertNull(onlyChild.nextNeighbour { id, count -> List(count) { ReadingEntry.Page(id, it) } })
    }

    @Test
    fun `a seam can name a book whose document is not open`() {
        val state = state(nextSegment = null)

        assertEquals(second.title, state.titleOf(second.id))
        assertEquals(third.title, state.titleOf(third.id))
        assertNull(state.titleOf(99))
    }

    @Test
    fun `a loaded neighbour is named from the segment that is open`() {
        val renamed = state(
            nextSegment = ReaderSegment(third.copy(title = "المجلد 3 (منسوخ)"), 2, true),
        )

        assertEquals("المجلد 3 (منسوخ)", renamed.titleOf(third.id))
    }

    // endregion
}
