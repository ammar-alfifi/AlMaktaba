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
        // **Only a page drawn as a composable turns this way.** A reflowable page is live text with
        // no pixels to bend, so [PageTurnEffect.CURL] is a rotation for it. A page that *is* a bitmap
        // — PDF, CBZ, CBR — bends its sheet instead, and the reader hands those pages an identity
        // transform here so that the two bends do not stack. See `PaperCurl.kt`.
        PageTurnEffect.CURL -> PageTurnTransform(
            // A page being turned swells very slightly as it comes off the block — enough to
            // separate it from the page beneath, not enough to look like a zoom.
            scale = 1f + CURL_SWELL * travel,
            // The sign is chosen so the page's free edge — the one away from its hinge — comes
            // *towards* the reader as it swings, which is what a sheet of paper does when it is
            // lifted. The opposite sign pushes it into the screen and reads as the page being
            // pressed flat instead.
            rotationY = distance * CURL_DEGREES,
            // The shading peaks around the middle of the turn and falls away at both ends: a page
            // just starting to lift is still lit from the front, and one nearly flat on the other
            // side has caught the light again.
            shadeAlpha = CURL_SHADE * kotlin.math.sin(travel * Math.PI).toFloat().coerceAtLeast(0f),
        )

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

/**
 * How far a page lifts, in degrees, at the furthest point of a turn.
 *
 * Short of ninety on purpose: a page that reaches a right angle to the screen vanishes to a line and
 * takes the reader's place in the text with it. At seventy degrees the page is unmistakably turning
 * while its last lines stay readable.
 */
private const val CURL_DEGREES = 70f

/** The page grows by this much, at most, as it comes off the block. */
private const val CURL_SWELL = 0.04f

/** The deepest shadow a curling page casts on itself. */
private const val CURL_SHADE = 0.34f

/** How much a sliding page shrinks by the time it is a whole page away. */
private const val SLIDE_SHRINK = 0.08f

/** How much a sliding page dims over the same distance. */
private const val SLIDE_DIM = 0.45f

/** A fading page shrinks only a little: the fade carries the effect, not the movement. */
private const val FADE_SHRINK = 0.03f
