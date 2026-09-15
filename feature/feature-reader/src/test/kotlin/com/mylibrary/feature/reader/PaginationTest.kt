package com.mylibrary.feature.reader

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the page-breaking arithmetic.
 *
 * These run against a [BlockMeasure] that answers instantly and lies freely, which is the point of
 * the interface: the question "which characters go on which page" is separable from "how tall is
 * this text", and only the second needs a text shaper. Entangled, none of this would be testable
 * off a device — and page breaks are exactly where a reflowable reader goes wrong: a page ending
 * mid-line, a block repeated on both pages, a reader stuck turning between two pages that show the
 * same thing, or a loop that never terminates.
 */
class PaginationTest {

    /**
     * Every line is 10px tall, 20 characters wide, and a block's text is as long as it says.
     */
    private class FakeMeasure(private val blocks: Map<Int, String>, private val lineHeight: Float = 10f) : BlockMeasure {

        private fun textOf(block: ContentBlock): String = when (block) {
            is ContentBlock.Paragraph -> block.text.text
            is ContentBlock.Heading -> block.text.text
            is ContentBlock.ListItem -> block.text.text
            is ContentBlock.Quote -> block.text.text
            is ContentBlock.Image -> ""
            is ContentBlock.Table -> ""
            ContentBlock.Divider -> ""
        }

        override fun lineCount(block: ContentBlock, widthPx: Int): Int {
            val length = textOf(block).length
            return if (length == 0) 0 else (length + CHARS_PER_LINE - 1) / CHARS_PER_LINE
        }

        override fun lineHeight(block: ContentBlock, line: Int, widthPx: Int): Float = lineHeight

        override fun lineStart(block: ContentBlock, line: Int, widthPx: Int): Int = line * CHARS_PER_LINE

        override fun lineEnd(block: ContentBlock, line: Int, widthPx: Int): Int =
            minOf((line + 1) * CHARS_PER_LINE, textOf(block).length)

        override fun wholeHeight(block: ContentBlock, widthPx: Int): Float =
            when (block) {
                ContentBlock.Divider -> 5f
                else -> lineCount(block, widthPx) * lineHeight
            }

        companion object {
            const val CHARS_PER_LINE = 20
        }
    }

    private fun paragraph(length: Int): ContentBlock.Paragraph =
        ContentBlock.Paragraph(AnnotatedString("ا".repeat(length)))

    private val measure = FakeMeasure(emptyMap())

    /** 100px of page fits ten 10px lines of a paragraph. */
    private fun pagesOf(
        blocks: List<ContentBlock>,
        pageHeight: Float = 100f,
        spacing: Float = 0f,
    ) = paginate(blocks, widthPx = 400, pageHeightPx = pageHeight, spacingPx = spacing, measure = measure)

    // region one block

    @Test
    fun `a block shorter than a page is one page`() {
        val pages = pagesOf(listOf(paragraph(40))) // two lines

        assertEquals(1, pages.size)
        assertEquals(listOf(BlockSlice(0, 0, 40)), pages[0].slices)
    }

    @Test
    fun `a block is cut at a line boundary, not mid-line`() {
        val pages = pagesOf(listOf(paragraph(250))) // 13 lines at 20 chars, 10 pages of 10 lines

        assertEquals(2, pages.size)
        // 100px holds ten lines = 200 characters.
        assertEquals(BlockSlice(0, 0, 200), pages[0].slices.single())
        assertEquals(BlockSlice(0, 200, 250), pages[1].slices.single())
    }

    @Test
    fun `a continuation starts where the previous page stopped`() {
        val pages = pagesOf(listOf(paragraph(250)))

        assertEquals(pages[0].slices.last().end, pages[1].slices.first().start)
    }

    /** The last character of the block must appear, exactly once, across the pages. */
    @Test
    fun `every character lands on exactly one page`() {
        val pages = pagesOf(listOf(paragraph(137)))

        for (page in pages) {
            for (slice in page.slices) {
                assertTrue("slice $slice is empty", slice.end > slice.start)
            }
        }
        val covered = pages.flatMap { page -> page.slices.map { it.start until it.end } }
        assertEquals((0 until 137).toList(), covered.flatMap { it.toList() })
    }

    // endregion

    // region many blocks

    @Test
    fun `blocks that fit together share a page`() {
        val pages = pagesOf(listOf(paragraph(20), paragraph(20), paragraph(20))) // 1 line each

        assertEquals(1, pages.size)
        assertEquals(3, pages[0].slices.size)
    }

    /**
     * A paragraph that runs past the bottom of the page fills what is left of it and continues on
     * the next, rather than being pushed down whole and leaving a half-empty page behind.
     */
    @Test
    fun `a long paragraph fills the page it started on and continues on the next`() {
        // Five lines, then eight: 130px of text against a 100px page.
        val pages = pagesOf(listOf(paragraph(100), paragraph(160)))

        assertEquals(2, pages.size)
        // The first paragraph is five lines; the second fills the remaining five lines of the page.
        assertEquals(
            listOf(BlockSlice(0, 0, 100), BlockSlice(1, 0, 100)),
            pages[0].slices,
        )
        assertEquals(listOf(BlockSlice(1, 100, 160)), pages[1].slices)
    }

    @Test
    fun `spacing counts towards the page height`() {
        // Four one-line blocks with 10px between them need 4*10 + 3*10 = 70px, so they fit...
        val withSpacing = pagesOf(
            blocks = listOf(paragraph(20), paragraph(20), paragraph(20), paragraph(20)),
            pageHeight = 70f,
            spacing = 10f,
        )
        assertEquals(1, withSpacing.size)

        // ...and at 60px only three fit, because the fourth would need its gap too.
        val tighter = pagesOf(
            blocks = listOf(paragraph(20), paragraph(20), paragraph(20), paragraph(20)),
            pageHeight = 60f,
            spacing = 10f,
        )
        assertEquals(2, tighter.size)
        assertEquals(3, tighter[0].slices.size)
        assertEquals(1, tighter[1].slices.size)
    }

    /** A block starting a page must not carry a gap from the page before it. */
    @Test
    fun `the first block on a page is flush with the top`() {
        val pages = pagesOf(
            blocks = listOf(paragraph(100), paragraph(100)), // five lines each
            pageHeight = 50f,
            spacing = 10f,
        )

        assertEquals(2, pages.size)
        assertEquals(1, pages[0].slices.size)
        assertEquals(1, pages[1].slices.size)
    }

    // endregion

    // region atomic blocks

    @Test
    fun `a divider is never split and moves whole to the next page`() {
        val pages = pagesOf(
            blocks = listOf(paragraph(40), ContentBlock.Divider),
            pageHeight = 24f, // two lines (20px) fit; the 5px divider does not join them
        )

        assertEquals(2, pages.size)
        assertEquals(BlockSlice(0, 0, 40), pages[0].slices.single())
        assertEquals(BlockSlice(1, 0, 0), pages[1].slices.single())
    }

    /**
     * An image taller than the page keeps a page to itself rather than being dropped: it is placed,
     * the page overflows and is clipped, and the text after it starts cleanly on the next page.
     * A page containing nothing at all would be one the reader cannot move on from.
     */
    @Test
    fun `a block taller than the page is placed rather than dropped`() {
        val giant = ContentBlock.Image(path = "cover.png", alt = null)
        val oversize = object : BlockMeasure by measure {
            override fun wholeHeight(block: ContentBlock, widthPx: Int): Float =
                if (block is ContentBlock.Image) 500f else measure.wholeHeight(block, widthPx)
        }

        val pages = paginate(
            blocks = listOf(paragraph(20), giant, paragraph(20)),
            widthPx = 400,
            pageHeightPx = 100f,
            spacingPx = 0f,
            measure = oversize,
        )

        assertEquals(3, pages.size)
        assertEquals(BlockSlice(0, 0, 20), pages[0].slices.single())
        assertEquals(BlockSlice(1, 0, 0), pages[1].slices.single())
        assertEquals(BlockSlice(2, 0, 20), pages[2].slices.single())
    }

    // endregion

    // region degenerate input

    @Test
    fun `no blocks is no pages`() {
        assertEquals(emptyList<ReaderPage>(), pagesOf(emptyList()))
    }

    @Test
    fun `a page with no height or no width produces no pages`() {
        assertEquals(emptyList<ReaderPage>(), pagesOf(listOf(paragraph(20)), pageHeight = 0f))
        assertEquals(
            emptyList<ReaderPage>(),
            paginate(listOf(paragraph(20)), widthPx = 0, pageHeightPx = 100f, spacingPx = 0f, measure = measure),
        )
    }

    /**
     * The case that would otherwise spin forever: a page shorter than a single line, which a
     * landscape phone at a very large font can genuinely produce. One line goes on the page and the
     * paginator moves on.
     */
    @Test
    fun `a page shorter than one line still terminates and places the text`() {
        val pages = pagesOf(listOf(paragraph(60)), pageHeight = 4f) // three lines, 10px each

        assertEquals(3, pages.size)
        assertEquals(listOf(0 to 20, 20 to 40, 40 to 60), pages.map { it.slices.single() }.map { it.start to it.end })
    }

    @Test
    fun `an empty paragraph does not create a page of its own`() {
        val pages = pagesOf(listOf(paragraph(0)))

        assertEquals(1, pages.size)
        assertEquals(BlockSlice(0, 0, 0), pages[0].slices.single())
    }

    // endregion

    // region position

    /** The offset a page reports is what makes reopening a book land on the same page. */
    @Test
    fun `a page reports where it begins in the chapter`() {
        val pages = pagesOf(listOf(paragraph(250)))

        assertEquals(0, pages[0].startOffset)
        assertEquals(200, pages[1].startOffset)
    }

    @Test
    fun `pages are in chapter order`() {
        val pages = pagesOf(listOf(paragraph(250)))

        assertEquals(pages.map { it.startOffset }.sorted(), pages.map { it.startOffset })
    }

    // endregion
}
