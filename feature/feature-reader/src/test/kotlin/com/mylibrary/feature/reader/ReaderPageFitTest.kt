package com.mylibrary.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for the geometry behind the page-fit setting.
 *
 * The setting was previously stored, offered in two settings screens and never read: choosing a fit
 * mode changed nothing about the page. These pin down what each mode now means in pixels, because
 * the failure mode is silent — a render box of the wrong *shape* does not fail, it comes back as a
 * stretched page, since pdfium maps a page onto whatever rectangle it is handed rather than
 * letterboxing it.
 */
class ReaderPageFitTest {

    private val viewport = IntSize(1080, 1920)

    /** A portrait page, twice as tall as it is wide — the common shape for a scanned book. */
    private val portraitPage = PageSize(600, 1200)

    // region render box

    @Test
    fun `page fit renders into the viewport`() {
        val box = renderBoxFor(PageFitMode.PAGE, viewport, resolutionStep = 1, pageSize = portraitPage)

        assertEquals(IntSize(1080, 1920), box)
    }

    @Test
    fun `width fit renders as tall as the page's own proportions require`() {
        val box = renderBoxFor(PageFitMode.WIDTH, viewport, resolutionStep = 1, pageSize = portraitPage)

        // 1080 wide, and the page is 2:1, so twice that.
        assertEquals(IntSize(1080, 2160), box)
    }

    @Test
    fun `actual size renders at the page's own dimensions`() {
        val box = renderBoxFor(
            PageFitMode.ACTUAL_SIZE,
            viewport,
            resolutionStep = 1,
            pageSize = portraitPage,
        )

        assertEquals(IntSize(600, 1200), box)
    }

    /**
     * Zooming asks for a sharper render, and every fit mode honours it. The viewport here is small
     * enough that doubling it stays inside the cap, so this asserts the multiplication rather than
     * the capping — which has its own test below.
     */
    @Test
    fun `the resolution step multiplies every fit mode`() {
        val small = IntSize(540, 960)

        assertEquals(IntSize(1080, 1920), renderBoxFor(PageFitMode.PAGE, small, 2, portraitPage))
        assertEquals(IntSize(1080, 2160), renderBoxFor(PageFitMode.WIDTH, small, 2, portraitPage))
        assertEquals(IntSize(1200, 2400), renderBoxFor(PageFitMode.ACTUAL_SIZE, small, 2, portraitPage))
    }

    /**
     * The cap applies to a zoomed render too, not only to a large page. Both dimensions shrink by
     * the same factor, so a zoomed-in width fit stays the shape of its page.
     */
    @Test
    fun `a zoomed render of a large viewport is capped and keeps its shape`() {
        val box = renderBoxFor(PageFitMode.WIDTH, viewport, resolutionStep = 2, pageSize = portraitPage)

        assertTrue("must fit the pixel budget, was $box", box.width.toLong() * box.height <= 8_000_000L)
        assertTrue("must fit the edge cap, was $box", box.height <= 4096)
        assertTrue("aspect drifted, was $box", abs(box.width.toDouble() / box.height - 0.5) < 0.01)
    }

    /**
     * Page fit is capped too, and this is the case that matters most: its box is the viewport, which
     * the doc comment used to call "bounded by the device" — true until the resolution step
     * multiplies it, at which point a phone asks the decoder for a 3240×7200 bitmap of a page it
     * will draw at 1080 pixels wide. One such page is 63 MB against a 64 MB cache.
     */
    @Test
    fun `a zoomed page fit is capped like every other mode`() {
        val box = renderBoxFor(PageFitMode.PAGE, IntSize(1080, 2400), resolutionStep = 3, pageSize = portraitPage)

        assertTrue("must fit the pixel budget, was $box", box.width.toLong() * box.height <= 8_000_000L)
        assertTrue("must fit the edge cap, was $box", box.height <= 4096)
        assertTrue("aspect drifted, was $box", abs(box.width.toDouble() / box.height - 0.45) < 0.01)
        // Still bigger than the viewport, so a zoomed page is genuinely sharper than an unzoomed one.
        assertTrue("a zoom step must still buy resolution, was $box", box.width > 1080)
    }

    /** Turning the page with the page's size still unknown takes the same fallback, capped the same way. */
    @Test
    fun `an unknown page size at a zoom step is capped as well`() {
        val box = renderBoxFor(PageFitMode.PAGE, IntSize(1080, 2400), resolutionStep = 3, pageSize = null)

        assertTrue("must fit the pixel budget, was $box", box.width.toLong() * box.height <= 8_000_000L)
        assertTrue("aspect drifted, was $box", abs(box.width.toDouble() / box.height - 0.45) < 0.01)
    }

    /**
     * A page that has not reported its size yet — or cannot — falls back to the viewport, which is
     * what the reader did for every page before the fit modes existed.
     */
    @Test
    fun `an unknown page size falls back to the viewport`() {
        for (mode in PageFitMode.entries) {
            assertEquals(
                "mode $mode",
                IntSize(1080, 1920),
                renderBoxFor(mode, viewport, resolutionStep = 1, pageSize = null),
            )
        }
    }

    @Test
    fun `a degenerate page size falls back to the viewport rather than dividing by zero`() {
        for (mode in PageFitMode.entries) {
            assertEquals(
                "mode $mode",
                IntSize(1080, 1920),
                renderBoxFor(mode, viewport, resolutionStep = 1, pageSize = PageSize(0, 900)),
            )
        }
    }

    @Test
    fun `a surface with no size yields no render box`() {
        for (mode in PageFitMode.entries) {
            assertEquals(IntSize.Zero, renderBoxFor(mode, IntSize.Zero, 1, portraitPage))
        }
    }

    /**
     * A tall page in a wide viewport: the requested height overflows an `Int` when computed in `Int`
     * arithmetic, which is why the box is computed in `Long` and then capped.
     */
    @Test
    fun `an extreme page ratio produces a capped box rather than an overflow`() {
        val box = renderBoxFor(
            PageFitMode.WIDTH,
            IntSize(4000, 2000),
            resolutionStep = 1,
            pageSize = PageSize(1, Int.MAX_VALUE),
        )

        assertTrue("box must be positive, was $box", box.width > 0 && box.height > 0)
        assertTrue("box must be capped, was $box", box.height <= 4096)
    }

    // endregion

    // region drawn size

    @Test
    fun `a page fit page is drawn letterboxed inside the frame`() {
        val drawn = drawnPageSize(
            bitmapWidth = 600,
            bitmapHeight = 1200,
            container = IntSize(1000, 1000),
            fitMode = PageFitMode.PAGE,
        )

        // Tall page, square frame: height is the binding constraint.
        assertEquals(500f, drawn.width, 0.01f)
        assertEquals(1000f, drawn.height, 0.01f)
    }

    @Test
    fun `a width fit page is drawn exactly as wide as the frame`() {
        val drawn = drawnPageSize(
            bitmapWidth = 600,
            bitmapHeight = 1200,
            container = IntSize(1000, 1000),
            fitMode = PageFitMode.WIDTH,
        )

        assertEquals(1000f, drawn.width, 0.01f)
        // ...and therefore taller than the frame, which is the whole point of the mode.
        assertEquals(2000f, drawn.height, 0.01f)
    }

    @Test
    fun `an actual size page is drawn at its own pixel dimensions`() {
        val drawn = drawnPageSize(
            bitmapWidth = 600,
            bitmapHeight = 1200,
            container = IntSize(1000, 1000),
            fitMode = PageFitMode.ACTUAL_SIZE,
        )

        assertEquals(Size(600f, 1200f), drawn)
    }

    @Test
    fun `a page with no pixels is not drawn at all`() {
        assertEquals(
            Size.Zero,
            drawnPageSize(0, 1200, IntSize(1000, 1000), PageFitMode.PAGE),
        )
        assertEquals(
            Size.Zero,
            drawnPageSize(600, 1200, IntSize.Zero, PageFitMode.PAGE),
        )
    }

    // endregion

    // region the reference a zoom is measured against

    /**
     * The bug this catches was reported as "the page jumps to a different size", and it could only
     * happen on actual size with a document whose decoder ignores a bigger render box.
     *
     * The reader re-renders a zoomed page at two or three times the resolution and measures the
     * stored zoom against the *base* render, so that the page does not change size when the render
     * gets sharper. For actual size that base is the page's own pixel size — and the size of the
     * bitmap on screen says nothing about it, because the archive decoder never upscales: asked for a
     * 1400-wide box it returns the same 700-wide bitmap as before. Deriving the base by dividing the
     * bitmap by the step therefore halved it, and the page came out at half the magnification the
     * reader asked for — which on screen is a page that shrinks the moment a pinch crosses 2×.
     */
    @Test
    fun `an actual-size page measures its zoom against the page, not the bitmap`() {
        // A decoder that ignored the bigger box: the page is still drawn 600 wide at step 3.
        val drawnNow = Size(600f, 1200f)

        assertEquals(
            "the base must be the page's own size, not the bitmap divided by the step",
            Size(600f, 1200f),
            referenceDrawnSizeFor(PageFitMode.ACTUAL_SIZE, drawnNow, portraitPage),
        )

        // ...and so the layer scale is the whole of the zoom, not a third of it.
        assertEquals(2f, layerScaleFor(2f, drawnNow, Size(600f, 1200f)), 0.0001f)
    }

    /** A decoder that *did* honour the bigger box is the case the division was written for. */
    @Test
    fun `an actual-size page from a decoder that upscales keeps its size across a step`() {
        val page = PageSize(600, 1200)
        val atStepThree = Size(1800f, 3600f)

        val reference = referenceDrawnSizeFor(PageFitMode.ACTUAL_SIZE, atStepThree, page)

        assertEquals(Size(600f, 1200f), reference)
        // Three times the pixels, a third of the layer scale: the same 600 pixels across.
        assertEquals(600f, atStepThree.width * layerScaleFor(1f, atStepThree, reference), 0.01f)
    }

    /** Page fit and width fit size the page from the viewport, so there is nothing to correct. */
    @Test
    fun `a viewport-fitted page is its own reference at every resolution`() {
        for (mode in listOf(PageFitMode.PAGE, PageFitMode.WIDTH)) {
            for (step in listOf(1, 2, 3)) {
                val drawn = drawnPageSize(600 * step, 1200 * step, viewport, mode)

                assertEquals(
                    "mode $mode at step $step",
                    drawn,
                    referenceDrawnSizeFor(mode, drawn, portraitPage),
                )
            }
        }
    }

    /** A page that never reported its size falls back to what is actually on screen. */
    @Test
    fun `an unknown page size falls back to the drawn size`() {
        val drawnNow = Size(600f, 1200f)

        assertEquals(drawnNow, referenceDrawnSizeFor(PageFitMode.ACTUAL_SIZE, drawnNow, null))
        assertEquals(drawnNow, referenceDrawnSizeFor(PageFitMode.ACTUAL_SIZE, drawnNow, PageSize(0, 0)))
    }

    // endregion

    // region panning

    @Test
    fun `panning is held inside a page that overflows on one axis only`() {
        val clamped = clampPan(
            offset = Offset(500f, 500f),
            drawn = Size(1000f, 2000f),
            container = IntSize(1000, 1000),
            scale = 1f,
        )

        // Nothing to pan horizontally — the page is exactly the frame's width.
        assertEquals(0f, clamped.x, 0.01f)
        // Half of the 1000px vertical overflow, which is as far as the page may be moved.
        assertEquals(500f, clamped.y, 0.01f)
    }

    @Test
    fun `zooming out widens how far a page may be panned`() {
        val clamped = clampPan(
            offset = Offset(9999f, 9999f),
            drawn = Size(1000f, 2000f),
            container = IntSize(1000, 1000),
            scale = 2f,
        )

        assertEquals(500f, clamped.x, 0.01f)
        assertEquals(1500f, clamped.y, 0.01f)
    }

    /** A page that fits has nowhere to go, so a stray drag must not slide it off the screen. */
    @Test
    fun `a page that fits cannot be panned at all`() {
        val clamped = clampPan(
            offset = Offset(300f, -300f),
            drawn = Size(500f, 800f),
            container = IntSize(1000, 1000),
            scale = 1f,
        )

        assertEquals(Offset.Zero, clamped)
    }

    // endregion

    // region capping

    @Test
    fun `a box within the budget is left alone`() {
        assertEquals(IntSize(1080, 1920), capRenderBox(1080, 1920))
    }

    @Test
    fun `an oversized box is shrunk to the budget`() {
        val capped = capRenderBox(10_000, 10_000)

        assertTrue("must fit the pixel budget, was $capped", capped.width.toLong() * capped.height <= 8_000_000L)
        assertTrue("must fit the edge cap, was $capped", capped.width <= 4096 && capped.height <= 4096)
    }

    /** Shrinking keeps the page's shape; a box squashed on one axis would render a stretched page. */
    @Test
    fun `capping preserves the page's proportions`() {
        val capped = capRenderBox(12_000, 3_000)

        val original = 12_000.0 / 3_000.0
        val result = capped.width.toDouble() / capped.height
        assertTrue("aspect drifted from $original to $result", abs(original - result) < 0.01)
    }

    /**
     * A page whose declared size is larger than an `Int` can hold, times a zoom step: the product
     * must saturate rather than wrap, because a wrapped product is negative and a negative box
     * renders nothing at all.
     */
    @Test
    fun `an absurd page size saturates rather than wrapping to a negative box`() {
        val box = renderBoxFor(
            PageFitMode.ACTUAL_SIZE,
            viewport,
            resolutionStep = 3,
            pageSize = PageSize(Int.MAX_VALUE, Int.MAX_VALUE),
        )

        assertTrue("box must be positive, was $box", box.width > 0 && box.height > 0)
        assertTrue("must fit the edge cap, was $box", box.width <= 4096 && box.height <= 4096)
    }

    @Test
    fun `a box with no area caps to nothing`() {
        assertEquals(IntSize.Zero, capRenderBox(0, 100))
        assertEquals(IntSize.Zero, capRenderBox(100, 0))
        assertEquals(IntSize.Zero, capRenderBox(-5, 100))
    }

    // endregion
}
