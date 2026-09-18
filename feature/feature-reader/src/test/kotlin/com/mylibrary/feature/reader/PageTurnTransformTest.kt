package com.mylibrary.feature.reader

import com.mylibrary.core.domain.model.PageTurnEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-turn effects.
 *
 * The tests are mostly about the property a reader notices immediately if it breaks: a page that is
 * *not* being turned must look exactly like a page that was never touched. Everything else — how far
 * a page lifts, how dark its shadow gets — is a judgement that only a device can settle, and is
 * asserted here only as a range so a careless edit cannot make a page spin or vanish.
 */
class PageTurnTransformTest {

    @Test
    fun `a settled page is untouched by every effect`() {
        for (effect in PageTurnEffect.entries) {
            for (isRtl in listOf(false, true)) {
                assertEquals(
                    "a page at rest must be identity for $effect",
                    PageTurnTransform.Identity,
                    pageTurnTransform(offsetFraction = 0f, effect = effect, isRtl = isRtl),
                )
            }
        }
    }

    @Test
    fun `a page a whole turn away is at the end of its effect`() {
        val slide = pageTurnTransform(-1f, PageTurnEffect.SLIDE, isRtl = false)
        assertTrue("the page has receded", slide.scale < 1f)
        assertTrue("but is recognisably itself", slide.scale > 0.85f)
        assertTrue("and is dimmed, not gone", slide.alpha in 0.4f..0.9f)

        val fade = pageTurnTransform(-1f, PageTurnEffect.FADE, isRtl = false)
        assertEquals("a page a full turn away has finished fading", 0f, fade.alpha, 0.001f)
    }

    /**
     * **The curl is not a transform, and that is the whole of it.**
     *
     * A page being curled is *bent* — its pixels are re-drawn band by band along a fold — which is not
     * something a scale, a rotation and a fade can express. This function used to try: a 70° rotation
     * with a gradient rectangle for a shadow, which reads as a card pivoting, because a plane cannot
     * bend. Both readers now draw the bend themselves and hand this an identity for the curl, so what
     * is pinned here is that the two never both act on the same page. A page bent twice looks like a
     * page being crushed.
     */
    @Test
    fun `the curl is not a transform of the page`() {
        for (offset in listOf(-1f, -0.5f, -0.02f, 0.02f, 0.5f, 1f)) {
            assertEquals(
                "the curl must leave the transform alone at $offset",
                PageTurnTransform.Identity,
                pageTurnTransform(offset, PageTurnEffect.CURL, isRtl = false),
            )
        }
    }

    @Test
    fun `only the slide and the fade rotate the page, and neither does`() {
        for (effect in PageTurnEffect.entries) {
            assertEquals(
                "a $effect turn is not a rotation",
                0f,
                pageTurnTransform(-0.5f, effect, isRtl = false).rotationY,
                0.0001f,
            )
        }
    }

    @Test
    fun `every effect is symmetric about the settled position`() {
        for (effect in PageTurnEffect.entries) {
            val leaving = pageTurnTransform(-0.4f, effect, isRtl = false)
            val arriving = pageTurnTransform(0.4f, effect, isRtl = false)

            assertEquals("$effect scales alike in both directions", leaving.scale, arriving.scale, 0.0001f)
            assertEquals("$effect fades alike in both directions", leaving.alpha, arriving.alpha, 0.0001f)
            assertEquals("$effect shades alike in both directions", leaving.shadeAlpha, arriving.shadeAlpha, 0.0001f)
            // The rotation is the one value that must *not* be symmetric: the two pages are hinged
            // on opposite edges and swing in opposite senses, which is what makes them one movement.
            assertEquals(
                "$effect rotates in opposite senses",
                -leaving.rotationY,
                arriving.rotationY,
                0.0001f,
            )
        }
    }

    @Test
    fun `a page hinges on the edge it is moving away from`() {
        // Left to right: the page leaving goes to the left, so it is hinged on its left; the page
        // arriving behind it is hinged on its right.
        assertEquals(0f, pageTurnPivotX(-0.5f, isRtl = false), 0.0001f)
        assertEquals(1f, pageTurnPivotX(0.5f, isRtl = false), 0.0001f)

        // Right to left: the same physical gesture, mirrored.
        assertEquals(1f, pageTurnPivotX(-0.5f, isRtl = true), 0.0001f)
        assertEquals(0f, pageTurnPivotX(0.5f, isRtl = true), 0.0001f)
    }

    @Test
    fun `a settled page hinges on its own centre`() {
        // Nothing is turning, so there is no hinge; the centre is the value that leaves the page
        // exactly where it is if the rotation is ever applied at rest.
        assertEquals(0.5f, pageTurnPivotX(0f, isRtl = false), 0.0001f)
        assertEquals(0.5f, pageTurnPivotX(0f, isRtl = true), 0.0001f)
    }

    @Test
    fun `a turn beyond a page's width is clamped rather than exaggerated`() {
        // The pager only reports offsets within a page or so, but a fling can overshoot briefly and
        // an unclamped value would spin a page past its own end.
        assertEquals(
            pageTurnTransform(-1f, PageTurnEffect.SLIDE, isRtl = false),
            pageTurnTransform(-1.4f, PageTurnEffect.SLIDE, isRtl = false),
        )
        assertEquals(
            pageTurnTransform(1f, PageTurnEffect.FADE, isRtl = false),
            pageTurnTransform(1.4f, PageTurnEffect.FADE, isRtl = false),
        )
    }

    @Test
    fun `an effect deepens with the distance turned`() {
        val quarter = pageTurnTransform(-0.25f, PageTurnEffect.SLIDE, isRtl = false)
        val half = pageTurnTransform(-0.5f, PageTurnEffect.SLIDE, isRtl = false)
        val whole = pageTurnTransform(-1f, PageTurnEffect.SLIDE, isRtl = false)

        assertTrue(quarter.scale > half.scale)
        assertTrue(half.scale > whole.scale)
    }
}
