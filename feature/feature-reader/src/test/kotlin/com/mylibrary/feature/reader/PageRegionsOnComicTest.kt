package com.mylibrary.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.mylibrary.core.domain.model.PageFitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble detector measured against whole drawn comic pages, rather than against arrays of
 * hand-written pixels.
 *
 * `PageRegionsTest` pins the arithmetic — which shapes are accepted, which gates reject what — and
 * it does that on images small enough to write out by hand. That is the right way to test a *gate*,
 * and the wrong way to test a *heuristic*: a hand-written fixture only ever contains the shapes its
 * author thought of, and the bug these tests were written for lived in the gap between the shapes
 * somebody thought of and the ones a comic actually contains. Every page here is rasterised from a
 * description by [ComicPages], and every assertion is about what the reader sees happen.
 *
 * The symptom being guarded against is specific, and it is not "the zoom does nothing". A region the
 * detector accepts is framed by `zoomTargetForRegion` only if framing it would magnify by
 * [MIN_USEFUL_ZOOM] or more; a region covering half the page is refused as not worth framing, and
 * the reader gets the plain double-tap zoom instead — the whole page, enlarged. To the reader that
 * is indistinguishable from the feature being absent, which is exactly how it was reported.
 */
class PageRegionsOnComicTest {

    /** What the reader's screen looks like to the renderer when a whole comic page is shown. */
    private val viewport = IntSize(1080, 2400)

    private val renderScale = 0.77f

    /** The page as it is rendered and handed to the detector on a double-tap. */
    private fun rendered(page: java.awt.image.BufferedImage): Rendered {
        val width = (ComicPages.WIDTH * renderScale).toInt()
        val height = (ComicPages.HEIGHT * renderScale).toInt()
        return Rendered(ComicPages.pixels(page, renderScale), width, height)
    }

    private class Rendered(val pixels: IntArray, val width: Int, val height: Int) {
        fun at(fraction: Pair<Float, Float>): BubbleRegion? =
            findBubbleRegion(pixels, width, height, (fraction.first * width).toInt(), (fraction.second * height).toInt())
    }

    /**
     * The magnification and position a double-tap on this page would actually produce.
     *
     * Deliberately built from the same calls the reader makes, in the same order, so that what is
     * asserted is the outcome rather than an intermediate number.
     */
    private fun framingOf(region: BubbleRegion): ZoomTarget {
        val bitmapWidth = ComicPages.WIDTH
        val bitmapHeight = ComicPages.HEIGHT
        val drawn = drawnPageSize(bitmapWidth, bitmapHeight, viewport, PageFitMode.PAGE)
        return zoomTargetForRegion(region.bounds, viewport, drawn, bitmapWidth, bitmapHeight)
    }

    // region the ordinary case

    /**
     * The tap a reader actually makes: **on the words**.
     *
     * A balloon holding a single short line has empty paper at its centre, and one holding two has a
     * line of lettering there. A detector that only works on the first is a detector that works on
     * the pages its author drew.
     */
    @Test
    fun `a double-tap on the lettering of a bubble frames that bubble`() {
        val page = rendered(ComicPages.page())

        val region = page.at(ComicPages.ROOMY_BUBBLE_TEXT)

        assertNotNull("the balloon the reader aimed at must be found", region)
        assertTrue(
            "the region must be the balloon, not the panel around it",
            region!!.bounds.area.toFloat() / (page.width * page.height) < 0.2f,
        )
        assertTrue(
            "framing it must magnify enough to be worth doing",
            framingOf(region).scale >= MIN_USEFUL_ZOOM,
        )
    }

    /** The same for a balloon with four lines crammed into it, where ink outweighs paper. */
    @Test
    fun `dense lettering does not lose the bubble it fills`() {
        val page = rendered(ComicPages.page())

        val region = page.at(ComicPages.CRAMPED_BUBBLE_TEXT)

        assertNotNull(region)
        assertTrue(framingOf(region!!).scale >= MIN_USEFUL_ZOOM)
    }

    /**
     * A thin outline is not a hole. Cheap print, a scan, and a small render all thin an outline out,
     * and the detector must not depend on it being thick.
     */
    @Test
    fun `a hairline outline still encloses its bubble`() {
        for (outline in listOf(3f, 2f, 1.5f, 1f)) {
            val page = rendered(ComicPages.page(outlineWidth = outline))

            val region = page.at(ComicPages.ROOMY_BUBBLE_TEXT)

            assertNotNull("outline $outline lost the balloon", region)
            assertTrue("outline $outline was not worth framing", framingOf(region!!).scale >= MIN_USEFUL_ZOOM)
        }
    }

    // endregion

    // region a broken outline

    /**
     * The regression, and the case that was reported.
     *
     * A balloon whose outline thins to nothing at one point let the fill out into the panel it sits
     * in. The panel was then a perfectly valid *region* by every gate the detector had — large, wide,
     * and mostly paper — so it was returned; and a region covering half the page is refused by the
     * reader as not worth framing, which left the plain zoom of the whole page. The bubble was found
     * and then thrown away, and the reader saw the feature do nothing.
     */
    @Test
    fun `a break in the outline does not hand back the panel instead of the bubble`() {
        for (gapPx in listOf(1f, 2f, 3f, 4f, 6f, 8f)) {
            val page = rendered(ComicPages.pageWithBrokenOutline(gapPx = gapPx))

            val region = page.at(ComicPages.ROOMY_BUBBLE_TEXT)

            assertNotNull("a $gapPx-pixel break lost the balloon", region)
            assertTrue(
                "a $gapPx-pixel break handed back ${region!!.bounds}, which is the panel",
                region.bounds.area.toFloat() / (page.width * page.height) < 0.2f,
            )
            assertTrue(
                "a $gapPx-pixel break was not worth framing",
                framingOf(region).scale >= MIN_USEFUL_ZOOM,
            )
        }
    }

    /**
     * A break wide enough to be a doorway is not sealed, and must not be: at that point the balloon
     * genuinely is not enclosed, and guessing would frame a rectangle the artist never drew. The
     * failure has to stay *safe* — the ordinary zoom, never a wrong rectangle.
     */
    @Test
    fun `a break too wide to seal falls back rather than guessing`() {
        val page = rendered(ComicPages.pageWithBrokenOutline(gapPx = 24f))

        val region = page.at(ComicPages.ROOMY_BUBBLE_TEXT)

        if (region != null) {
            assertTrue(
                "a sealed-through break must not produce a rectangle worth framing, got ${region.bounds}",
                framingOf(region).scale < MIN_USEFUL_ZOOM,
            )
        }
    }

    // endregion

    // region the page's own edges

    /** Tapping blank panel is not a request to zoom into a balloon, and there is no balloon there. */
    @Test
    fun `tapping empty panel space does not frame the panel`() {
        val page = rendered(ComicPages.page())

        val region = page.at(0.5f to 0.25f)

        if (region != null) {
            assertTrue(
                "the panel is not worth framing, got ${region.bounds}",
                framingOf(region).scale < MIN_USEFUL_ZOOM,
            )
        }
    }

    /** The page margin runs off the edge of the page, so it is not an enclosed region at all. */
    @Test
    fun `the page margin is never a region`() {
        val page = rendered(ComicPages.page())

        assertNull(page.at(0.02f to 0.5f))
    }

    // endregion

    /** The detector is handed the page's own pixels, so the shapes it finds are the drawn ones. */
    @Test
    fun `the bubble found is the bubble drawn`() {
        val page = rendered(ComicPages.page())

        val region = page.at(ComicPages.ROOMY_BUBBLE_TEXT)

        assertNotNull(region)
        val bounds = region!!.bounds
        val centre = Offset(
            bounds.centerX / page.width,
            bounds.centerY / page.height,
        )
        assertEquals(ComicPages.ROOMY_BUBBLE_TEXT.first, centre.x, 0.05f)
        assertEquals(ComicPages.ROOMY_BUBBLE_TEXT.second, centre.y, 0.05f)

        // The drawn balloon is 620x380 in page pixels, so its frame at this render is about 0.44 of
        // the page's width; a frame twice that would be the panel, half of it a fragment.
        val widthFraction = bounds.width.toFloat() / page.width
        assertTrue("framed $bounds, which is not the balloon", widthFraction in 0.30f..0.60f)
    }

}
