package com.mylibrary.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

/**
 * How the page is drawn right now: scaled about the centre of its slot, then translated.
 *
 * **[scale] is measured against the page's *reference* render, not against whatever render happens to
 * be on screen.** The reader re-renders a zoomed page at two or three times the resolution, and for
 * an actual-size page that render is literally that many times larger — so a scale defined against
 * "the page as drawn" would double the magnification the moment the resolution stepped up, which is
 * a visible jump in the middle of a pinch. Measured against the reference render, crossing a step
 * changes only the sharpness. [layerScaleFor] is the conversion, and it is the only place that knows
 * about the difference.
 *
 * [offset] is in view pixels.
 */
internal data class PageTransform(
    val scale: Float,
    val offset: Offset,
) {
    companion object {
        val Identity = PageTransform(1f, Offset.Zero)
    }
}

/**
 * The scale to hand `graphicsLayer` for a page currently drawn at [drawn] against a reference render
 * of [reference].
 *
 * For a page-fitted or width-fitted page the two are the same size and this is the identity — the
 * drawn size of those modes comes from the viewport, not from the bitmap. For an actual-size page a
 * sharper render is drawn *larger*, so the layer scale divides by the same factor, and the page
 * occupies the same number of screen pixels at every resolution.
 */
internal fun layerScaleFor(scale: Float, drawn: Size, reference: Size): Float {
    if (drawn.width <= 0f || reference.width <= 0f) return scale
    return scale * (reference.width / drawn.width)
}

/**
 * The inverse of [layerScaleFor]: the reference-relative zoom a layer scale corresponds to.
 *
 * Used when a destination has been worked out from what is on screen — a region framed to 85% of the
 * viewport is expressed in the scale the layout sees — and has to be stored in the scale the state
 * keeps, which must not change when the render's resolution does.
 */
internal fun referenceScaleFor(layerScale: Float, drawn: Size, reference: Size): Float {
    if (drawn.width <= 0f || reference.width <= 0f) return layerScale
    return layerScale * (drawn.width / reference.width)
}

/**
 * Where a zoom is going: which point of the page should end up where on screen, and at what scale.
 *
 * **Why this is not just a scale and an offset.** The reader re-renders a page at a higher
 * resolution once the user zooms past a whole step, and for an actual-size page that render is
 * literally bigger — so the page's drawn size changes underneath a transform expressed in pixels,
 * and the view jumps. A destination expressed as *this point of the page belongs at this point on
 * screen* survives that: it is recomputed against whatever size the page is drawn at, so a sharper
 * render arriving mid-animation re-lands the same point in the same place instead of moving the
 * page.
 *
 * **Why the anchor is a fraction and not a pixel.** A pixel coordinate only means anything relative
 * to one particular render. Re-rendering doubles the bitmap, so the same point of the *page* is at
 * double the pixel coordinates, and a target holding pixels would land somewhere else. As a fraction
 * of the page it is the same point in every render at every resolution.
 */
internal data class ZoomTarget(
    /** The point of the page to bring to [anchorView], as a fraction of the page in 0..1. */
    val anchorFraction: Offset,
    /** Where on screen it should land, in view pixels. */
    val anchorView: Offset,
    val scale: Float,
)

/**
 * The view transform that realises [target], clamped to the pan limits.
 *
 * The page's drawn rectangle is centred in the container, so the transform for a point `r` measured
 * from that centre is `view = centre + r * scale + offset`. Solving for the anchor gives the offset
 * below, which is then clamped by the same [clampPan] the pan gesture uses — a destination that the
 * user could not have reached by dragging must not be reachable by zooming either.
 */
internal fun transformFor(
    target: ZoomTarget,
    container: IntSize,
    drawn: Size,
    bitmapWidth: Int,
    bitmapHeight: Int,
): PageTransform {
    if (drawn.width <= 0f || drawn.height <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return PageTransform.Identity
    }

    val scale = target.scale.coerceIn(MIN_SCALE, MAX_SCALE)
    val scaleToDrawn = drawn.width / bitmapWidth
    val anchorFromCentre = Offset(
        x = target.anchorFraction.x * bitmapWidth * scaleToDrawn - drawn.width / 2f,
        y = target.anchorFraction.y * bitmapHeight * (drawn.height / bitmapHeight) - drawn.height / 2f,
    )
    val centre = Offset(container.width / 2f, container.height / 2f)
    val offset = target.anchorView - centre - anchorFromCentre * scale

    return PageTransform(
        scale = scale,
        offset = clampPan(offset, drawn, container, scale),
    )
}

/**
 * The page pixel under a point on screen, or `null` when that point is not on the page.
 *
 * "Not on the page" is a real answer, not a failure: a page-fitted page is letterboxed on a screen
 * of a different shape, and a tap in that margin is a tap on the background. It falls through to
 * the ordinary zoom, which is what a tap on the background should do.
 */
internal fun viewPointToPixel(
    viewPoint: Offset,
    container: IntSize,
    drawn: Size,
    transform: PageTransform,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Offset? {
    if (drawn.width <= 0f || drawn.height <= 0f) return null
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return null

    val centre = Offset(container.width / 2f, container.height / 2f)
    // Undo the translation, then the scale, then the page's own placement in its slot.
    val viewFromCentre = viewPoint - centre - transform.offset
    val drawnFromCentre = viewFromCentre / transform.scale.coerceAtLeast(MIN_SCALE)
    val drawnFromPageOrigin = drawnFromCentre + Offset(drawn.width / 2f, drawn.height / 2f)

    val scaleToBitmap = bitmapWidth / drawn.width
    val pixel = Offset(
        x = drawnFromPageOrigin.x * scaleToBitmap,
        y = drawnFromPageOrigin.y * (bitmapHeight / drawn.height),
    )

    if (pixel.x < 0f || pixel.y < 0f) return null
    if (pixel.x >= bitmapWidth || pixel.y >= bitmapHeight) return null
    return pixel
}

/** The point on screen where a page pixel is drawn — the inverse of [viewPointToPixel]. */
internal fun pixelToViewPoint(
    pixel: Offset,
    container: IntSize,
    drawn: Size,
    transform: PageTransform,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Offset {
    if (drawn.width <= 0f || drawn.height <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return Offset(container.width / 2f, container.height / 2f)
    }

    val scaleToDrawn = drawn.width / bitmapWidth
    val fromCentre = Offset(
        x = pixel.x * scaleToDrawn - drawn.width / 2f,
        y = pixel.y * (drawn.height / bitmapHeight) - drawn.height / 2f,
    )
    val centre = Offset(container.width / 2f, container.height / 2f)
    return centre + fromCentre * transform.scale + transform.offset
}

/**
 * A destination that frames [region]: its centre brought to the middle of the screen and its size
 * grown until the tighter of its two axes spans [fill] of the container.
 *
 * The tighter axis, because a wide bubble on a tall screen is limited by its width and a tall panel
 * by its height; filling both exactly would need a non-uniform scale, which would stretch the
 * artwork.
 */
internal fun zoomTargetForRegion(
    region: PixelRect,
    container: IntSize,
    drawn: Size,
    bitmapWidth: Int,
    bitmapHeight: Int,
    fill: Float = FOCUS_FILL,
): ZoomTarget {
    val centre = Offset(container.width / 2f, container.height / 2f)
    val anchor = Offset(
        x = if (bitmapWidth > 0) region.centerX / bitmapWidth else 0.5f,
        y = if (bitmapHeight > 0) region.centerY / bitmapHeight else 0.5f,
    )

    if (drawn.width <= 0f || bitmapWidth <= 0) {
        return ZoomTarget(anchorFraction = anchor, anchorView = centre, scale = MIN_SCALE)
    }

    val scaleToDrawn = drawn.width / bitmapWidth
    val regionOnScreen = Size(
        width = max(region.width * scaleToDrawn, 1f),
        height = max(region.height * scaleToDrawn, 1f),
    )
    val scale = fill * min(
        container.width / regionOnScreen.width,
        container.height / regionOnScreen.height,
    )

    return ZoomTarget(
        anchorFraction = anchor,
        anchorView = centre,
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
    )
}

/** The destination that puts the page back where it started: whole, centred, at 1×. */
internal fun identityTarget(bitmapWidth: Int, bitmapHeight: Int, container: IntSize): ZoomTarget =
    ZoomTarget(
        anchorFraction = Offset(0.5f, 0.5f),
        anchorView = Offset(container.width / 2f, container.height / 2f),
        scale = MIN_SCALE,
    )

/**
 * The fraction of the page a point in bitmap pixels sits at.
 *
 * The conversion between the two coordinate systems a zoom deals in: a hit test produces pixels
 * (that is what indexes the page's pixels), while a destination is expressed as a fraction so it
 * survives a re-render.
 */
internal fun pixelToPageFraction(
    pixel: Offset,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Offset = Offset(
    x = if (bitmapWidth > 0) pixel.x / bitmapWidth else 0.5f,
    y = if (bitmapHeight > 0) pixel.y / bitmapHeight else 0.5f,
)

/**
 * The transform [stored] describes once the page is drawn at [drawnNow] rather than [drawnWhenSet].
 *
 * The two differ when the *slot* changes size — a rotation, a split-screen resize — and the pan was
 * measured against the old one. Scaling the translation by the same ratio keeps whatever page pixel
 * was under the centre of the screen under it, at unchanged magnification. A change in the render's
 * own resolution is not this: that is handled by [layerScaleFor], and deliberately does not move
 * anything.
 */
internal fun rebaseTransform(
    stored: PageTransform,
    drawnWhenSet: Size,
    drawnNow: Size,
    container: IntSize,
): PageTransform {
    if (drawnWhenSet.width <= 0f || drawnWhenSet.height <= 0f) return stored
    if (drawnNow.width <= 0f || drawnNow.height <= 0f) return PageTransform.Identity
    if (drawnWhenSet == drawnNow) return stored

    val rebased = Offset(
        x = stored.offset.x * (drawnNow.width / drawnWhenSet.width),
        y = stored.offset.y * (drawnNow.height / drawnWhenSet.height),
    )
    return PageTransform(
        scale = stored.scale,
        offset = clampPan(rebased, drawnNow, container, stored.scale),
    )
}

/**
 * A point along the way from [from] to [to], for animating a zoom.
 *
 * The scale interpolates geometrically rather than linearly: zoom is multiplicative, so a linear
 * ramp from 1× to 4× spends most of its time in the last doubling and reads as a lurch. Both
 * endpoints are exact, including at [fraction] 0 and 1.
 */
internal fun lerpTransform(from: PageTransform, to: PageTransform, fraction: Float): PageTransform {
    val t = fraction.coerceIn(0f, 1f)
    if (t == 0f) return from
    if (t == 1f) return to

    return PageTransform(
        scale = from.scale * (to.scale / from.scale).pow(t),
        offset = Offset(
            x = from.offset.x + (to.offset.x - from.offset.x) * t,
            y = from.offset.y + (to.offset.y - from.offset.y) * t,
        ),
    )
}

private fun Float.pow(exponent: Float): Float = kotlin.math.exp(exponent * kotlin.math.ln(this))

/**
 * The share of the container a framed region fills on its tighter axis.
 *
 * Short of the whole screen so the region does not touch the edges — a bubble bleeding off the side
 * of the display reads as a mis-framed zoom rather than as a bubble you are looking at.
 */
internal const val FOCUS_FILL = 0.85f

/**
 * Below this scale, zooming is not worth doing: the region covers so much of the page that framing
 * it would barely change what the reader sees, and the plain zoom at the tapped point is more use.
 */
internal const val MIN_USEFUL_ZOOM = 1.15f

/** The scale range the reader allows. */
/**
 * Above this a page counts as magnified, and a gesture on it counts as the reader's rather than the
 * pager's or the column's. A hair over 1 rather than exactly 1, because a pinch that has just
 * started leaves the scale at 1.0000001 and a drag at that magnification is still a page turn.
 */
internal const val ZOOMED_THRESHOLD = 1.01f

/** Long enough to read as movement, short enough not to be waited for. */
internal const val ZOOM_TWEEN_MS = 280

/** The magnification a double-tap opens at, when there is no speech bubble to frame instead. */
internal const val DOUBLE_TAP_SCALE = 2.5f

/** The sharpest a page is ever re-rendered at. Bucketed, so a pinch does not re-render per frame. */
internal const val MAX_RENDER_SCALE = 3f

internal const val MIN_SCALE = 1f
internal const val MAX_SCALE = 6f
