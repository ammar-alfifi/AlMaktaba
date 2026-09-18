package com.mylibrary.feature.reader

import com.mylibrary.core.domain.model.PageTurnEffect

/**
 * How one page is drawn while a turn is in progress.
 *
 * Every value is a deviation from "settled": the identity transform is a page sitting still.
 */
internal data class PageTurnTransform(
    val scale: Float = 1f,
    val alpha: Float = 1f,
    /**
     * Degrees about the vertical axis, and the pivot that makes it read as paper rather than as a
     * spinning card. See [pageTurnPivotX].
     */
    val rotationY: Float = 0f,
    /** How dark the page's own shading is, 0..1 — the shadow a lifting page casts on itself. */
    val shadeAlpha: Float = 0f,
) {
    companion object {
        val Identity = PageTurnTransform()
    }
}

/**
 * The transform for a page sitting [offsetFraction] pages away from the settled position.
 *
 * **Where this runs.** The pagers call it from inside `graphicsLayer`, against the value
 * `PagerState.getOffsetDistanceInPages` reports for that page. That is the whole point: the value
 * changes continuously *while the finger is down*, so every effect here is driven by the gesture
 * rather than played after it. A page that animates only once the swipe has been released is a
 * slideshow; a page that follows the thumb is a book.
 *
 * **The sign.** `offsetFraction` is signed and runs about -1..1. Negative means the page is leaving
 * towards the leading edge in the reading direction; positive means it is arriving from the trailing
 * edge. Zero is a settled page, and returns [PageTurnTransform.Identity] for every effect — which
 * is the property that matters most, because a settled page must look exactly like a page that was
 * never moved.
 *
 * **There is no horizontal translation here, deliberately.** The pager already moves pages, and its
 * translation is measured in its own page-pitch units including the gap between pages. Adding a
 * second translation on top would fight it, and compensating for it exactly would mean knowing that
 * pitch and its sign in both layout directions — which cannot be verified without a device. The
 * effects below therefore work *with* the pager's motion: rotation and shading for the curl, scale
 * and opacity for the slide and the fade.
 *
 * @param isRtl which way the book reads, which is what decides which edge a turning page is hinged
 *   on. Getting this wrong mirrors the effect rather than breaking it, and `PageTurnTransformTest`
 *   pins the mirroring down.
 */
internal fun pageTurnTransform(
    offsetFraction: Float,
    effect: PageTurnEffect,
    isRtl: Boolean,
): PageTurnTransform {
    val distance = offsetFraction.coerceIn(-1f, 1f)
    if (distance == 0f) return PageTurnTransform.Identity

    val travel = kotlin.math.abs(distance)

    return when (effect) {
        // **The curl is not a transform of the page — the page bends, and draws that itself.**
        //
        // Bending a sheet means re-drawing its pixels band by band along a fold, which no scale, no
        // rotation and no fade can express; a transform can move a page, and the whole point of a
        // curl is that the page is no longer a plane. So the reader draws the bend ([drawPaperCurl])
        // and hands this function an identity for it, in every layout and for every format — a
        // reflowable page is drawn into a layer and bent exactly like a comic's.
        //
        // What used to be here was a 70° rotation with a gradient rectangle standing in for the
        // shadow. It read as a card pivoting, because that is what it was.
        PageTurnEffect.CURL -> PageTurnTransform.Identity

        PageTurnEffect.SLIDE -> PageTurnTransform(
            scale = 1f - SLIDE_SHRINK * travel,
            // The outgoing page dims as it goes and the incoming one brightens as it arrives, which
            // is what gives the slide a near and a far side instead of two flat cards passing.
            alpha = 1f - SLIDE_DIM * travel,
        )

        PageTurnEffect.FADE -> PageTurnTransform(
            // A dissolve rather than a cut: opacity reaches zero as the page reaches a full page
            // away, so two pages crossing mid-swipe are seen through each other instead of side by
            // side. The pager's own motion is still there underneath it; the fade is what the eye
            // reads.
            alpha = 1f - travel,
            scale = 1f - FADE_SHRINK * travel,
        )
    }
}

/**
 * The x fraction about which a turning page is hinged, 0 being its leading edge and 1 its trailing.
 *
 * A page hinges on the edge it is moving *away* from — the edge that is still attached to the book.
 * In a left-to-right book the page that is leaving moves left, so its hinge is on its left; the page
 * arriving behind it is hinged on its right, which is the edge it swings from. Mirroring the two in
 * Arabic gives the same physical gesture in both directions.
 */
internal fun pageTurnPivotX(offsetFraction: Float, isRtl: Boolean): Float {
    if (offsetFraction == 0f) return 0.5f
    val hingesOnLeadingEdge = offsetFraction < 0f
    val leading = if (isRtl) 1f else 0f
    return if (hingesOnLeadingEdge) leading else 1f - leading
}

/** How much a sliding page shrinks by the time it is a whole page away. */
private const val SLIDE_SHRINK = 0.08f

/** How much a sliding page dims over the same distance. */
private const val SLIDE_DIM = 0.45f

/** A fading page shrinks only a little: the fade carries the effect, not the movement. */
private const val FADE_SHRINK = 0.03f
