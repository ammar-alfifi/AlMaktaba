package com.mylibrary.feature.reader

/**
 * One entry of the order the reader is moving through.
 *
 * A device folder is a series, so the reader draws the volume before the open one above it and the
 * volume after it below — in every presentation, in both directions. That makes the thing the pager
 * counts or the column scrolls not "the pages of the open document" but **the reading order**, and
 * this is what one entry of it is: a unit of one book of the folder, or the blank page where two
 * books meet.
 *
 * The three unit cases are the three granularities the reader actually has. A page-image book is a
 * list of pages; a reflowed book shown as a column is a list of chapters; the same book shown as
 * pages is a list of pages *within* chapters, because that is what the paginator produces and what
 * its window holds. Naming them separately rather than flattening them into one index is what keeps
 * the entries meaningful on their own — an index into a list nobody else has says nothing, and that
 * is the mistake `PageRef` was introduced to fix one level down.
 *
 * Every case carries the [bookId] it belongs to, because the open book is no longer the only book
 * on screen: the renderer has to know *which* document a page comes from, and the position report
 * has to know when the reader has crossed into another one.
 */
internal sealed interface ReadingEntry {
    /** A page of a page-image book — a PDF, a CBZ — in either presentation. */
    data class Page(val bookId: Long, val pageIndex: Int) : ReadingEntry

    /** A chapter of a reflowed book drawn as a column. */
    data class Chapter(val bookId: Long, val chapterIndex: Int) : ReadingEntry

    /** A page of a reflowed book drawn as pages: a position inside one of its chapters. */
    data class TextPage(
        val bookId: Long,
        val chapterIndex: Int,
        val pageIndexInChapter: Int,
    ) : ReadingEntry

    /**
     * The blank page between two books of the folder.
     *
     * It is an entry like any other rather than a decoration drawn over the last page, which is the
     * whole of the design: a turn reaches it the way it reaches any other page — from either
     * direction — and the reader is neither in [fromBookId] nor in [toBookId] while it is on
     * screen. Nothing is reported as a position while the reader is on it, so the progress bar keeps
     * naming the book they have just finished, which is the truthful answer.
     */
    data class Seam(val fromBookId: Long, val toBookId: Long) : ReadingEntry
}

/**
 * The key an entry is filed under, for the compositions that keep their place by identity.
 *
 * A `HorizontalPager` and a `LazyColumn` both renumber everything they hold when the list under them
 * changes, and both will keep the reader on the item they were looking at instead — but only if
 * every item has a key, and only if the key outlives the renumbering. The open book's own pages are
 * therefore not enough: a page of the next volume has to keep its identity when that volume becomes
 * the open one, which happens the moment the reader crosses the seam and is exactly the frame in
 * which a key that said "page 3" would point at a page of a different book.
 *
 * A string rather than a number, because Compose files an item's saved state under its key and hands
 * it to the platform as a `Bundle`, which holds what the platform knows: the key of a page used to be
 * two numbers packed into a `Long`, which was exact while a page was named by its chapter and its
 * place in it. A book id on top of those two does not fit in 64 bits without lying about how wide
 * they are, and a collision here is one page wearing another's state.
 */
internal fun ReadingEntry.readingKey(): String = when (this) {
    is ReadingEntry.Page -> "page:$bookId:$pageIndex"
    is ReadingEntry.Chapter -> "chapter:$bookId:$chapterIndex"
    is ReadingEntry.TextPage -> "text:$bookId:$chapterIndex:$pageIndexInChapter"
    is ReadingEntry.Seam -> "seam:$fromBookId>$toBookId"
}

/**
 * A neighbouring book, and the entries it contributes to the reading order.
 *
 * [units] is empty when the book exists in the folder but is not open yet — still being opened
 * ahead of the reader, or refused, or written in a way this reader draws differently. The seam is
 * still placed, because a seam says what comes next whether or not the next thing is ready: the
 * alternative is a reader reaching the end of a volume and finding nothing at all there, which is
 * the state the panel existed to avoid.
 */
internal data class ReadingNeighbour(val bookId: Long, val units: List<ReadingEntry>)

/**
 * The reading order a presentation draws: the open book's units, its neighbours' units, and the
 * blank page at every seam between two books.
 *
 * A pure function of its arguments, deliberately. Every presentation builds its own units — pages,
 * chapters, or pages-within-chapters — and each needs the same three things done to them: the
 * previous book above, the next book below, and a seam wherever two books meet. Done here once,
 * with keys, index mapping and the seam's identity decided in one place, rather than four times in
 * four files that would drift apart by the first bug.
 */
internal fun readingOrder(
    primaryBookId: Long,
    primary: List<ReadingEntry>,
    previous: ReadingNeighbour? = null,
    next: ReadingNeighbour? = null,
): List<ReadingEntry> = buildList {
    previous?.let { neighbour ->
        addAll(neighbour.units)
        add(ReadingEntry.Seam(fromBookId = neighbour.bookId, toBookId = primaryBookId))
    }
    addAll(primary)
    next?.let { neighbour ->
        add(ReadingEntry.Seam(fromBookId = primaryBookId, toBookId = neighbour.bookId))
        addAll(neighbour.units)
    }
}

/** The entry the reader is looking at, or `null` past either end of the order. */
internal fun List<ReadingEntry>.entryAt(index: Int): ReadingEntry? = getOrNull(index)

/**
 * Where the reader's position in [bookId] sits in the order: the entry at or before [pageIndex].
 *
 * "At or before" rather than "at", because a remembered position can name a page that no longer
 * exists — a book re-laid out with a larger font has fewer pages in a chapter than it did, and a
 * saved page 12 has to land on the last page rather than nowhere. The same rounding answers for a
 * jump into the middle of a book and for one past its end.
 */
internal fun List<ReadingEntry>.indexOfPage(bookId: Long, pageIndex: Int): Int =
    indexOfLast { it is ReadingEntry.Page && it.bookId == bookId && it.pageIndex <= pageIndex }

/** Where a chapter of [bookId] sits in the order, rounded the same way. */
internal fun List<ReadingEntry>.indexOfChapter(bookId: Long, chapterIndex: Int): Int =
    indexOfLast { it is ReadingEntry.Chapter && it.bookId == bookId && it.chapterIndex <= chapterIndex }

/** Where a page of a chapter of [bookId] sits in the order, rounded the same way. */
internal fun List<ReadingEntry>.indexOfTextPage(
    bookId: Long,
    chapterIndex: Int,
    pageIndexInChapter: Int,
): Int = indexOfLast {
    it is ReadingEntry.TextPage && it.bookId == bookId && it.chapterIndex == chapterIndex &&
        it.pageIndexInChapter <= pageIndexInChapter
}

/**
 * The reader's position as the order needs it, for the book on one side of the open one.
 *
 * The book is taken from the loaded segment where there is one and from the folder's own sequence
 * otherwise, so the order places the seam for a neighbour whose document is not open — see
 * [ReadingNeighbour]. The units come from the segment, because how many units a book has is a
 * question only its open document can answer.
 */
internal fun ReaderUiState.previousNeighbour(
    units: (bookId: Long, count: Int) -> List<ReadingEntry>,
): ReadingNeighbour? = neighbour(segment = previousSegment, bookId = sequence?.previous?.id, units = units)

/** The book on the other side of the open one, resolved the same way. */
internal fun ReaderUiState.nextNeighbour(
    units: (bookId: Long, count: Int) -> List<ReadingEntry>,
): ReadingNeighbour? = neighbour(segment = nextSegment, bookId = sequence?.next?.id, units = units)

private fun neighbour(
    segment: ReaderSegment?,
    bookId: Long?,
    units: (bookId: Long, count: Int) -> List<ReadingEntry>,
): ReadingNeighbour? {
    val id = segment?.book?.id ?: bookId ?: return null
    return ReadingNeighbour(id, segment?.let { units(id, it.unitCount) }.orEmpty())
}

/**
 * The title of any book of the reader's sequence, by id — what a seam names.
 *
 * A seam carries ids rather than titles because a title is not what makes two books different, and
 * every book of the sequence has a title whether or not its document is open: the loaded segment
 * answers first, and the folder's own sequence answers for a neighbour the reader could not open.
 * Both are consulted rather than one being assumed, which is what lets the seam name a book the
 * reader is not going to be able to continue into.
 */
internal fun ReaderUiState.titleOf(bookId: Long): String? {
    // Held in locals because a property of a class in another module cannot be smart-cast, and the
    // alternative — `sequence?.previous?.title` inside each branch — reads as if the two neighbours
    // came from different places.
    val previous = sequence?.previous
    val next = sequence?.next
    return when (bookId) {
        book?.id -> book?.title
        previousSegment?.book?.id -> previousSegment?.book?.title
        nextSegment?.book?.id -> nextSegment?.book?.title
        previous?.id -> previous?.title
        next?.id -> next?.title
        else -> null
    }
}
