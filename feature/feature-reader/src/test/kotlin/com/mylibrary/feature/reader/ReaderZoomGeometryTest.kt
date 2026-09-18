package com.mylibrary.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.mylibrary.core.domain.model.PageFitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that connects a finger on the glass to a pixel on the page, and back.
 *
 * Two properties are worth more than the rest, and most of these tests are about them. A tap must
 * map to the pixel the reader *aimed at* — a zoom that lands somewhere else is worse than no zoom.
 * And a page must not move when it is re-rendered more sharply, because the reader re-renders
 * mid-gesture and a jump there looks like the app dropped the reader's place.
 */
class ReaderZoomGeometryTest {

    // region Tap to page, and back

    @Test
    fun `a tap at the centre of a page-fit page maps to the middle of the bitmap`() {
        val pixel = viewPointToPixel(
            viewPoint = Offset(500f, 1000f),
            container = VIEWPORT,
            drawn = Size(1000f, 2000f),
            transform = PageTransform.Identity,
            bitmapWidth = 500,
            bitmapHeight = 1000,
        )

        assertNotNull(pixel)
        assertEquals(250f, pixel!!.x, 0.01f)
        assertEquals(500f, pixel.y, 0.01f)
    }

    @Test
    fun `a tap in the letterbox margin maps to nothing`() {
        // A square page in a tall viewport: the page is 1000x1000, centred, so the top of the screen
        // is background. A tap there is a tap on the background, and the reader's ordinary zoom is
        // the right answer for it.
        val letterboxed = viewPointToPixel(
            viewPoint = Offset(500f, 250f),
            container = VIEWPORT,
            drawn = Size(1000f, 1000f),
            transform = PageTransform.Identity,
            bitmapWidth = 500,
            bitmapHeight = 500,
        )

        assertNull(letterboxed)
    }

    @Test
    fun `a tap below the letterbox maps into the page`() {
        val pixel = viewPointToPixel(
            viewPoint = Offset(500f, 750f),
            container = VIEWPORT,
            drawn = Size(1000f, 1000f),
            transform = PageTransform.Identity,
            bitmapWidth = 500,
            bitmapHeight = 500,
        )

        assertNotNull(pixel)
        assertEquals(250f, pixel!!.x, 0.01f)
        assertEquals(125f, pixel.y, 0.01f)
    }

    @Test
    fun `the mapping round-trips through a zoom and a pan`() {
        val transform = PageTransform(scale = 2.5f, offset = Offset(-120f, 300f))
        val pixel = viewPointToPixel(
            viewPoint = Offset(420f, 900f),
            container = VIEWPORT,
            drawn = Size(1000f, 2000f),
            transform = transform,
            bitmapWidth = 500,
            bitmapHeight = 1000,
        )

        assertNotNull(pixel)
        val back = pixelToViewPoint(
            pixel = pixel!!,
            container = VIEWPORT,
            drawn = Size(1000f, 2000f),
            transform = transform,
            bitmapWidth = 500,
            bitmapHeight = 1000,
        )
        assertEquals(420f, back.x, 0.01f)
        assertEquals(900f, back.y, 0.01f)
    }

    @Test
    fun `zooming and panning change where a tap lands`() {
        val unzoomed = viewPointToPixel(
            Offset(500f, 1000f), VIEWPORT, Size(1000f, 2000f),
            PageTransform.Identity, 500, 1000,
        )
        val zoomed = viewPointToPixel(
            Offset(500f, 1000f), VIEWPORT, Size(1000f, 2000f),
            PageTransform(scale = 2f, offset = Offset.Zero), 500, 1000,
        )

        assertNotNull(unzoomed)
        assertNotNull(zoomed)
        // At the centre of an unzoomed page is the middle of the bitmap; the same screen point on a
        // page zoomed to 2x about that centre is still the middle, and a point away from it is not.
        assertEquals(250f, zoomed!!.x, 0.01f)
        val offCentreUnzoomed = viewPointToPixel(
            Offset(750f, 1000f), VIEWPORT, Size(1000f, 2000f),
            PageTransform.Identity, 500, 1000,
        )
        val offCentreZoomed = viewPointToPixel(
            Offset(750f, 1000f), VIEWPORT, Size(1000f, 2000f),
            PageTransform(scale = 2f, offset = Offset.Zero), 500, 1000,
        )
        assertEquals(375f, offCentreUnzoomed!!.x, 0.01f)
        assertEquals(312.5f, offCentreZoomed!!.x, 0.01f)
    }

    // endregion

    // region Framing a region

    @Test
    fun `framing a region puts its centre at the centre of the screen`() {
        val region = PixelRect(left = 400, top = 800, right = 600, bottom = 1000)
        val target = zoomTargetForRegion(
            region = region,
            container = VIEWPORT,
            drawn = Size(1000f, 2000f),
            bitmapWidth = 1000,
            bitmapHeight = 2000,
        )
        val transform = transformFor(target, VIEWPORT, Size(1000f, 2000f), 1000, 2000)

        val onScreen = pixelToViewPoint(
            pixel = Offset(region.centerX, region.centerY),
            container = VIEWPORT,
            drawn = Size(1000f, 2000f),
            transform = transform,
            bitmapWidth = 1000,
            bitmapHeight = 2000,
        )

        assertEquals(500f, onScreen.x, 0.5f)
        assertEquals(1000f, onScreen.y, 0.5f)
    }

    @Test
    fun `framing a region grows it to fill the tighter axis`() {
        val region = PixelRect(left = 400, top = 800, right = 600, bottom = 1000)
        val target = zoomTargetForRegion(region, VIEWPORT, Size(1000f, 2000f), 1000, 2000)
        val transform = transformFor(target, VIEWPORT, Size(1000f, 2000f), 1000, 2000)

        // The region is 200x200 of a 1000x2000 page, drawn 1:1, in a 1000x2000 viewport: it is
        // limited by the width, so it should end up 85% of 1000 wide.
        val left = pixelToViewPoint(Offset(400f, 900f), VIEWPORT, Size(1000f, 2000f), transform, 1000, 2000)
        val right = pixelToViewPoint(Offset(600f, 900f), VIEWPORT, Size(1000f, 2000f), transform, 1000, 2000)
        assertEquals(850f, right.x - left.x, 1f)
    }

    @Test
    fun `a region in the corner is clamped to the page`() {
        val region = PixelRect(left = 0, top = 0, right = 100, bottom = 100)
        val target = zoomTargetForRegion(region, VIEWPORT, Size(1000f, 2000f), 1000, 2000)
        val transform = transformFor(target, VIEWPORT, Size(1000f, 2000f), 1000, 2000)

        // The pan clamp is what stops a zoom from framing a corner with blank space beside it.
        val slackX = (1000f * transform.scale - VIEWPORT.width) / 2f
        val slackY = (2000f * transform.scale - VIEWPORT.height) / 2f
        assertTrue(transform.offset.x in -slackX..slackX)
        assertTrue(transform.offset.y in -slackY..slackY)
    }

    @Test
    fun `the scale is clamped to the reader's range`() {
        val tiny = PixelRect(left = 500, top = 1000, right = 505, bottom = 1005)
        val target = zoomTargetForRegion(tiny, VIEWPORT, Size(1000f, 2000f), 1000, 2000)

        assertEquals(MAX_SCALE, target.scale, 0.001f)
    }

    @Test
    fun `framing survives a sharper render arriving underneath it`() {
        // The property the fractional anchor and the reference-relative scale exist for. A zoom is
        // aimed at a point of the *page*; re-rendering doubles the bitmap and draws the page twice
        // as large — which is what an actual-size page does — and the same point must still land in
        // the same place, at the same magnification.
        val region = PixelRect(left = 400, top = 800, right = 600, bottom = 1000)
        val container = IntSize(1000, 1000)

        val target = zoomTargetForRegion(region, container, Size(1000f, 1000f), 1000, 1000)
        val coarse = transformFor(target, container, Size(1000f, 1000f), 1000, 1000)

        // One step sharper: the page is drawn at 2000 and the layer scale halves, exactly as
        // `layerScaleFor` computes it.
        val sharp = transformFor(
            target = target.copy(scale = layerScaleFor(target.scale, Size(2000f, 2000f), Size(1000f, 1000f))),
            container = container,
            drawn = Size(2000f, 2000f),
            bitmapWidth = 2000,
            bitmapHeight = 2000,
        )

        val onScreenCoarse = pixelToViewPoint(
            Offset(region.centerX, region.centerY), container, Size(1000f, 1000f),
            coarse, 1000, 1000,
        )
        val onScreenSharp = pixelToViewPoint(
            // The same point of the page, at twice the pixel coordinates.
            Offset(region.centerX * 2f, region.centerY * 2f), container, Size(2000f, 2000f),
            sharp, 2000, 2000,
        )

        assertEquals(onScreenCoarse.x, onScreenSharp.x, 0.5f)
        assertEquals(onScreenCoarse.y, onScreenSharp.y, 0.5f)
        // And the page is no more magnified in one than in the other.
        assertEquals(
            coarse.scale * 1000f,
            sharp.scale * 2000f,
            0.5f,
        )
    }

    @Test
    fun `a sharper render does not change the stored magnification of a page`() {
        // `layerScaleFor` is the whole mechanism: the drawn size doubles, the layer scale halves.
        val reference = Size(600f, 1200f)
        val drawn = Size(1200f, 2400f)

        assertEquals(1f, layerScaleFor(1f, drawn, reference) * 2f, 0.0001f)
        assertEquals(2.5f, layerScaleFor(2.5f, drawn, reference) * 2f, 0.0001f)
        assertEquals(
            "the conversion round-trips",
            2.5f,
            referenceScaleFor(layerScaleFor(2.5f, drawn, reference), drawn, reference),
            0.0001f,
        )
    }

    @Test
    fun `the identity target realises the identity transform`() {
        val target = identityTarget(bitmapWidth = 500, bitmapHeight = 1000, container = VIEWPORT)
        val transform = transformFor(target, VIEWPORT, Size(1000f, 2000f), 500, 1000)

        assertEquals(1f, transform.scale, 0.0001f)
        assertEquals(0f, transform.offset.x, 0.0001f)
        assertEquals(0f, transform.offset.y, 0.0001f)
    }

    // endregion

    // region Surviving a re-render

    @Test
    fun `a page-fit page is drawn the same size at every render step`() {
        // This is why page fit and width fit never jumped when a sharper render landed: their drawn
        // size comes from the viewport, not from the bitmap.
        val coarse = drawnPageSize(500, 1000, VIEWPORT, com.mylibrary.core.domain.model.PageFitMode.PAGE)
        val sharp = drawnPageSize(1000, 2000, VIEWPORT, com.mylibrary.core.domain.model.PageFitMode.PAGE)

        assertEquals(coarse.width, sharp.width, 0.01f)
        assertEquals(coarse.height, sharp.height, 0.01f)
    }

    @Test
    fun `an actual-size page is drawn bigger at a sharper step`() {
        val coarse = drawnPageSize(
            600, 1200, VIEWPORT, com.mylibrary.core.domain.model.PageFitMode.ACTUAL_SIZE,
        )
        val sharp = drawnPageSize(
            1200, 2400, VIEWPORT, com.mylibrary.core.domain.model.PageFitMode.ACTUAL_SIZE,
        )

        assertEquals(coarse.width * 2f, sharp.width, 0.01f)
    }

    @Test
    fun `rebasing keeps whatever was under the centre of the screen under it`() {
        // An actual-size page zoomed to 2x, then re-rendered one step sharper: the page pixel at the
        // centre of the screen must not change.
        val zoomed = PageTransform(scale = 2f, offset = Offset(120f, -80f))
        val before = Size(600f, 1200f)
        val after = Size(1200f, 2400f)

        val rebased = rebaseTransform(zoomed, drawnWhenSet = before, drawnNow = after, container = VIEWPORT)

        fun centrePixel(transform: PageTransform, drawn: Size, bitmapWidth: Int, bitmapHeight: Int) =
            viewPointToPixel(Offset(500f, 1000f), VIEWPORT, drawn, transform, bitmapWidth, bitmapHeight)

        val pixelBefore = centrePixel(zoomed, before, 600, 1200)
        val pixelAfter = centrePixel(rebased, after, 1200, 2400)

        assertNotNull(pixelBefore)
        assertNotNull(pixelAfter)
        assertEquals(pixelBefore!!.x * 2f, pixelAfter!!.x, 0.5f)
        assertEquals(pixelBefore.y * 2f, pixelAfter.y, 0.5f)
        assertEquals("magnification is unchanged", zoomed.scale, rebased.scale, 0.0001f)
    }

    @Test
    fun `rebasing is the identity when the drawn size does not change`() {
        val zoomed = PageTransform(scale = 2f, offset = Offset(120f, -80f))
        val size = Size(1000f, 2000f)

        val rebased = rebaseTransform(zoomed, drawnWhenSet = size, drawnNow = size, container = VIEWPORT)

        assertEquals(zoomed, rebased)
    }

    // endregion

    // region Animating

    @Test
    fun `an interpolated zoom hits both endpoints exactly`() {
        val from = PageTransform.Identity
        val to = PageTransform(scale = 4f, offset = Offset(-300f, 200f))

        assertEquals(from, lerpTransform(from, to, 0f))
        assertEquals(to, lerpTransform(from, to, 1f))
    }

    @Test
    fun `an interpolated zoom moves the scale geometrically, not linearly`() {
        val from = PageTransform.Identity
        val to = PageTransform(scale = 4f, offset = Offset.Zero)

        // Half way through the motion is a doubling, not a 2.5x: a linear ramp spends most of its
        // time in the last doubling and reads as a lurch.
        assertEquals(2f, lerpTransform(from, to, 0.5f).scale, 0.001f)
    }

    @Test
    fun `an interpolated zoom is clamped to its range`() {
        val from = PageTransform.Identity
        val to = PageTransform(scale = 4f, offset = Offset.Zero)

        assertEquals(1f, lerpTransform(from, to, -1f).scale, 0.001f)
        assertEquals(4f, lerpTransform(from, to, 2f).scale, 0.001f)
    }

    // endregion

    // region the handover from the column

    /**
     * The bug this exists to fix. The column draws a page wide and the overlay draws the same page
     * fitted to the screen — two different rectangles — so a pinch that carried its magnification
     * over unchanged magnified whichever view the overlay happened to have, and the page changed size
     * at the moment of the handover.
     */
    @Test
    fun `a page the two fits size differently keeps its size across the handover`() {
        // A short, wide page: the column draws it 1000 wide, the overlay only 900, because at this
        // page's proportions the screen's height is what limits the fit.
        val columnDrawn = Size(1000f, 500f)
        val overlayDrawn = Size(900f, 450f)

        val pinched = ColumnMagnify(
            anchorFraction = Offset(0.5f, 0.5f),
            anchorView = Offset(500f, 1000f),
            zoom = 2f,
            columnDrawn = columnDrawn,
        )

        // Twice what the column was showing, not twice the overlay's smaller idea of 1×.
        val scale = continuityScale(columnDrawn, overlayDrawn)
        assertEquals(1000f / 900f, scale, 0.0001f)
        assertEquals(2f * 1000f / 900f, pinched.toTarget(overlayDrawn).scale, 0.0001f)
    }

    /** The common case: both fits agree, and the handover must then change nothing at all. */
    @Test
    fun `a page the two fits size the same has nothing to correct`() {
        val drawn = Size(1000f, 1500f)
        val pinched = ColumnMagnify(Offset(0.3f, 0.7f), Offset(300f, 900f), zoom = 1.5f, columnDrawn = drawn)

        assertEquals(1f, continuityScale(drawn, drawn), 0.0001f)
        assertEquals(1.5f, pinched.toTarget(drawn).scale, 0.0001f)
    }

    /** The pinch point is what must stay put; the scale is only what makes that possible. */
    @Test
    fun `the handover aims at the point under the fingers`() {
        val fraction = Offset(0.2f, 0.8f)
        val view = Offset(150f, 1750f)
        val pinched = ColumnMagnify(fraction, view, zoom = 1.2f, columnDrawn = Size(1000f, 1500f))

        val target = pinched.toTarget(Size(1000f, 1500f))

        assertEquals(fraction, target.anchorFraction)
        assertEquals(view, target.anchorView)
    }

    /** Before either page has been measured there is no ratio to apply, and 1 is the honest one. */
    @Test
    fun `a size that is not known yet is treated as no correction`() {
        assertEquals(1f, continuityScale(Size.Zero, Size(900f, 450f)), 0.0001f)
        assertEquals(1f, continuityScale(Size(1000f, 500f), Size.Zero), 0.0001f)
    }

    /**
     * The property the reader actually sees: whatever the two fits were doing, the page occupies the
     * same number of screen pixels before and after the handover.
     */
    @Test
    fun `the handed-over page is drawn the size the column was drawing it`() {
        val viewport = IntSize(1000, 2000)
        val bitmapWidth = 800
        val bitmapHeight = 1600

        // Width fit in the column, page fit in the overlay — the two rectangles for one page.
        val columnDrawn = drawnPageSize(bitmapWidth, bitmapHeight, viewport, PageFitMode.WIDTH)
        val overlayDrawn = drawnPageSize(bitmapWidth, bitmapHeight, viewport, PageFitMode.PAGE)

        val pinched = ColumnMagnify(Offset(0.5f, 0.5f), Offset(500f, 1000f), zoom = 1f, columnDrawn = columnDrawn)
        val transform = transformFor(
            target = pinched.toTarget(overlayDrawn),
            container = viewport,
            drawn = overlayDrawn,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
        )

        assertEquals(columnDrawn.width, overlayDrawn.width * transform.scale, 0.01f)
    }

    // endregion

    private companion object {
        val VIEWPORT = IntSize(1000, 2000)
    }
}
