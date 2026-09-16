package com.mylibrary.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble detector, tested against pages built pixel by pixel.
 *
 * The tests exist because the feature's whole value is in *not* being wrong: a detector that framed
 * a black outline, or a leaked fill covering the page, would zoom somewhere the reader did not ask
 * for. Every case below is therefore either "this is a bubble, frame exactly this" or "this is not a
 * bubble, refuse" — the two answers the reader acts on.
 */
class PageRegionsTest {

    // region A bubble is found

    @Test
    fun `a bubble interior is found and bounded inside its outline`() {
        val page = page(200, 200)
        page.strokeRect(left = 50, top = 50, right = 120, bottom = 100, thickness = 3, color = BLACK)

        val region = findBubbleRegion(page.pixels, page.width, page.height, x = 85, y = 75)

        assertNotNull("a white area enclosed by ink is a bubble", region)
        // The interior, plus the framing margin — never the outline itself, which would put a black
        // line along the edge of the zoomed view.
        assertTrue("left edge excludes the outline", region!!.bounds.left >= 50)
        assertTrue("top edge excludes the outline", region.bounds.top >= 50)
        assertTrue("right edge excludes the outline", region.bounds.right <= 120)
        assertTrue("bottom edge excludes the outline", region.bounds.bottom <= 100)
        assertTrue("the region is the interior", region.bounds.width >= 60)
    }

    @Test
    fun `a tap on the outlined edge still finds the bubble behind it`() {
        val page = page(200, 200)
        page.strokeRect(left = 50, top = 50, right = 120, bottom = 100, thickness = 3, color = BLACK)

        // The outline is what the eye follows when aiming at a bubble, so a tap on it has to work.
        val region = findBubbleRegion(page.pixels, page.width, page.height, x = 50, y = 75)

        assertNotNull("a tap on the outline finds the interior behind it", region)
    }

    @Test
    fun `a panel between gutters is found`() {
        // A page of four panels: the gutters are ink, so a tap inside one panel fills that panel.
        val page = page(200, 200)
        page.fillRect(0, 0, 200, 200, BLACK)
        page.fillRect(4, 4, 96, 96, WHITE)
        page.fillRect(104, 4, 196, 96, WHITE)
        page.fillRect(4, 104, 96, 196, WHITE)
        page.fillRect(104, 104, 196, 196, WHITE)

        val region = findBubbleRegion(page.pixels, page.width, page.height, x = 50, y = 50)

        assertNotNull("each panel is a region", region)
        assertTrue("the region stays within its own panel", region!!.bounds.right <= 104)
        assertTrue(region.bounds.bottom <= 104)
    }

    @Test
    fun `a shaded bubble is found when the tap lands on it`() {
        // A scan whose paper has yellowed, and a bubble filled with pale grey rather than white.
        val page = page(200, 200)
        page.fillRect(0, 0, 200, 200, 0xFFF2E8C8.toInt())
        page.strokeRect(40, 40, 140, 110, thickness = 3, color = BLACK)
        page.fillRect(43, 43, 137, 107, 0xFFE8E0C0.toInt())

        val region = findBubbleRegion(page.pixels, page.width, page.height, x = 90, y = 75)

        assertNotNull("the fill compares against the tap, not against white", region)
    }

    @Test
    fun `the same bubble is found at a coarser render and its bounds scale with it`() {
        val small = page(100, 100).apply {
            strokeRect(20, 20, 60, 50, thickness = 2, color = BLACK)
        }
        val large = page(300, 300).apply {
            strokeRect(60, 60, 180, 150, thickness = 6, color = BLACK)
        }

        val smallRegion = findBubbleRegion(small.pixels, small.width, small.height, 40, 35)
        val largeRegion = findBubbleRegion(large.pixels, large.width, large.height, 120, 105)

        assertNotNull(smallRegion)
        assertNotNull(largeRegion)
        // Three times the render, three times the region — within a pixel or two of rounding.
        val ratio = largeRegion!!.bounds.width.toFloat() / smallRegion!!.bounds.width
        assertTrue("bounds scale with the render, not with a fixed pixel size", ratio in 2.8f..3.2f)
    }

    // endregion

    // region Nothing is found, and the caller falls back

    @Test
    fun `tapping a solid ink area finds nothing`() {
        val page = page(200, 200)
        page.fillRect(60, 60, 140, 140, BLACK)

        assertNull(
            "a tap on ink is not a place to read",
            findBubbleRegion(page.pixels, page.width, page.height, 100, 100),
        )
    }

    @Test
    fun `a region that reaches the page border is refused`() {
        // The page's own margin: light, connected, and not a bubble. Framing it would zoom to
        // roughly where the reader already is.
        val page = page(200, 200)

        assertNull(
            "the margin is not an enclosed region",
            findBubbleRegion(page.pixels, page.width, page.height, 10, 10),
        )
    }

    @Test
    fun `a leak through a broken outline is refused rather than mis-measured`() {
        val page = page(200, 200)
        page.strokeRect(50, 50, 150, 150, thickness = 3, color = BLACK)
        // A gap in the outline: the fill escapes into the page margin, which reaches the border.
        page.fillRect(95, 48, 105, 53, WHITE)

        assertNull(
            "a leaked fill is refused, never returned as a plausible rectangle",
            findBubbleRegion(page.pixels, page.width, page.height, 100, 100),
        )
    }

    @Test
    fun `a region covering most of the page is refused`() {
        val page = page(200, 200)
        page.strokeRect(2, 2, 198, 198, thickness = 3, color = BLACK)

        assertNull(
            "nothing is gained by zooming into almost the whole page",
            findBubbleRegion(page.pixels, page.width, page.height, 100, 100),
        )
    }

    @Test
    fun `a speck is refused`() {
        val page = page(300, 300)
        page.strokeRect(150, 150, 156, 156, thickness = 2, color = BLACK)

        assertNull(
            "a few pixels of white in a halftone is not a bubble",
            findBubbleRegion(page.pixels, page.width, page.height, 153, 153),
        )
    }

    @Test
    fun `a sliver is refused even when its area passes the gate`() {
        val page = page(300, 300)
        // A hundred pixels of seam two pixels wide: past the area gate, and still not a region.
        page.strokeRect(100, 150, 200, 152, thickness = 1, color = BLACK)

        assertNull(
            "a seam between panels is not a region",
            findBubbleRegion(page.pixels, page.width, page.height, 150, 151),
        )
    }

    @Test
    fun `a region whose bounding box is mostly ink is refused`() {
        val page = page(300, 300)
        // A thin plus sign of paper inside a grey field, all of it enclosed by an outline: the
        // bounding box is the whole box and the region is a sixth of it.
        page.fillRect(90, 90, 210, 210, GREY)
        page.fillRect(145, 95, 155, 205, WHITE)
        page.fillRect(95, 145, 205, 155, WHITE)
        page.strokeRect(88, 88, 212, 212, thickness = 2, color = BLACK)

        assertNull(
            "a shape that does not fill its own box is treated as texture",
            findBubbleRegion(page.pixels, page.width, page.height, 150, 150),
        )
    }

    @Test
    fun `a tap outside the page finds nothing`() {
        val page = page(50, 50)

        assertNull(findBubbleRegion(page.pixels, page.width, page.height, 50, 25))
        assertNull(findBubbleRegion(page.pixels, page.width, page.height, 25, -1))
    }

    @Test
    fun `degenerate dimensions and a short array find nothing`() {
        val page = page(50, 50)

        assertNull(findBubbleRegion(page.pixels, 0, 0, 0, 0))
        assertNull(findBubbleRegion(IntArray(10), 50, 50, 5, 5))
    }

    // endregion

    /** A page of white paper, ready to have ink drawn on it. */
    private fun page(width: Int, height: Int) = TestPage(width, height)

    private class TestPage(val width: Int, val height: Int) {
        val pixels = IntArray(width * height) { WHITE }

        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) {
                for (x in left until right) {
                    if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
                }
            }
        }

        /** Draws the outline of a rectangle in ink, [thickness] pixels wide. */
        fun strokeRect(left: Int, top: Int, right: Int, bottom: Int, thickness: Int, color: Int) {
            fillRect(left, top, right, top + thickness, color)
            fillRect(left, bottom - thickness, right, bottom, color)
            fillRect(left, top, left + thickness, bottom, color)
            fillRect(right - thickness, top, right, bottom, color)
        }
    }

    private companion object {
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()
        val GREY = 0xFF909090.toInt()
    }
}
