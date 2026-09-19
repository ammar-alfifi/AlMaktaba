package com.mylibrary.feature.reader

/**
 * A page's identity: the chapter it belongs to, and its position in that chapter.
 *
 * The pager is one flat sequence of pages, and with several chapters in it at once a flat index says
 * almost nothing — it changes when a chapter is added to either end, and the page it names is only
 * meaningful next to the list it came from. A `(chapter, page)` pair does not: it survives the list
 * being rebuilt, which makes it the key the pager can be given so that it keeps its place across a
 * change of window. See [windowChapters].
 */
internal data class PageRef(val chapterIndex: Int, val pageIndexInChapter: Int)

/**
 * The page's identity as one number, which is the form the pager can be given it in.
 *
 * Compose files a page's saved state under the key and *stores that key in a `Bundle`*, which holds
 * only the types the platform knows — so the pair itself is not a legal key, however unique it is.
 * `PageRef` as a key compiles, and throws the moment a paged chapter is first composed.
 *
 * Packing the two halves into a `Long` keeps the identity exact rather than hashing it: a collision
 * would give two pages one page's state, and neither a chapter index nor a page number comes near
 * the 32 bits each half is given.
 */
internal fun PageRef.pagerKey(): Long =
    (chapterIndex.toLong() shl 32) or (pageIndexInChapter.toLong() and 0xFFFFFFFFL)

/**
 * The chapters a paged reader holds at once: the one being read, and one on each side of it.
 *
 * Holding one chapter — which is what the reader used to do — makes the end of a chapter a dead end:
 * there is no page after the last one for a turn to reach, so the reader had to be offered a button
 * to press. Holding the neighbours too puts the chapter's first page after the previous chapter's
 * last page and its last page before the next chapter's first, and then a turn crosses the seam
 * without anything special happening at it — forwards and backwards alike.
 *
 * Being a pure function of [currentUnit] is the property that matters, and it is what a window
 * *cannot* be if it is widened only when an edge is needed: a chapter of one page is both its own
 * first and last page, so "the page before this one is missing" and "the page after it is missing"
 * are true together, and a rule that answers each by sliding would slide back and forth forever. The
 * list below changes exactly once per chapter crossed, and never because of where in the chapter the
 * reader has got to.
 *
 * The cost is that the neighbours are laid out whether or not they are reached, so re-pagination —
 * a font size, a margin, a rotation — measures three chapters where it used to measure one.
 */
internal fun windowChapters(currentUnit: Int, totalUnits: Int): List<Int> {
    if (totalUnits <= 0) return emptyList()
    val current = currentUnit.coerceIn(0, totalUnits - 1)
    return ((current - 1)..(current + 1)).filter { it in 0 until totalUnits }
}

/**
 * The pager's pages: every page of every chapter in [chapters], in reading order.
 *
 * A chapter contributes exactly as many pages as [pageCounts] gives it, so a chapter that paginated
 * to nothing — a section holding only whitespace, an empty spine item — contributes no pages at all
 * and the turn into the next one happens between two pages that exist. That is the same dead end the
 * window exists to remove, one level down.
 */
internal fun windowPages(chapters: List<Int>, pageCounts: Map<Int, Int>): List<PageRef> =
    chapters.flatMap { chapter ->
        (0 until (pageCounts[chapter] ?: 0)).map { page -> PageRef(chapter, page) }
    }

/** The offset a page begins at, in the text of its own chapter. */
internal fun pageOffset(page: ReaderPage, offsets: ChapterTextMap): Int =
    page.slices.firstOrNull()?.let { offsets.startOf(it.blockIndex) + it.start } ?: 0

/**
 * The page of a chapter a remembered position lands on.
 *
 * A followed link names the block it targets, and the page holding it wins. Otherwise it is the last
 * page beginning at or before [anchorOffset] — the page the reader was reading, found again after
 * the text has been laid out differently. That is the property a page *number* cannot have: it names
 * a place in a pagination that no longer exists.
 *
 * `-1` when the chapter has no pages to land on, which the caller must leave alone rather than
 * carrying on to the first page of a chapter that has nothing in it.
 */
internal fun landingPageIn(
    pages: List<ReaderPage>,
    offsets: ChapterTextMap,
    anchorOffset: Int,
    anchorBlock: Int?,
): Int {
    if (anchorBlock != null) {
        val atAnchor = pages.indexOfFirst { page -> page.slices.any { it.blockIndex == anchorBlock } }
        if (atAnchor >= 0) return atAnchor
    }
    if (pages.isEmpty()) return -1
    return pages.indices.lastOrNull { pageOffset(pages[it], offsets) <= anchorOffset } ?: 0
}

/**
 * The pager index showing page [pageIndexInChapter] of [chapterIndex] — or the last page of that
 * chapter before it, when there is no such page.
 *
 * Both callers want that rounding rather than an error. A remembered character offset is the reason:
 * it is where the reader was reading, and after the font has changed it can land past the end of a
 * chapter that now has fewer pages, which must put them on the last page rather than nowhere. The
 * same call answers for a jump into the middle of a chapter, and for one into a chapter with no
 * pages at all — which is `-1`, and means the reader is to be left where they are.
 */
internal fun indexOfPage(pages: List<PageRef>, chapterIndex: Int, pageIndexInChapter: Int): Int =
    pages.indexOfLast { it.chapterIndex == chapterIndex && it.pageIndexInChapter <= pageIndexInChapter }
