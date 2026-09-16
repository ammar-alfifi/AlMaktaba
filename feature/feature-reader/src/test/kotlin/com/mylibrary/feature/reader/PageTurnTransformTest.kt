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
        val curl = pageTurnTransform(-1f, PageTurnEffect.CURL, isRtl = false)
        assertTrue("the page has swung away", curl.rotationY <= -45f)
        assertTrue("and not so far that it vanishes", curl.rotationY >= -85f)

        val slide = pageTurnTransform(-1f, PageTurnEffect.SLIDE, isRtl = false)
        assertTrue("the page has receded", slide.scale < 1f)
        assertTrue("but is recognisably itself", slide.scale > 0.85f)
        assertTrue("and is dimmed, not gone", slide.alpha in 0.4f..0.9f)

        val fade = pageTurnTransform(-1f, PageTurnEffect.FADE, isRtl = false)
        assertEquals("a page a full turn away has finished fading", 0f, fade.alpha, 0.001f)
    }

    @Test
    fun `a curl shades a page while it is in the air and not at either end`() {
        val halfway = pageTurnTransform(-0.5f, PageTurnEffect.CURL, isRtl = false)
        val starting = pageTurnTransform(-0.02f, PageTurnEffect.CURL, isRtl = false)

        assertTrue("a page in the air casts a shadow", halfway.shadeAlpha > 0.1f)
        assertTrue(
            "a page barely lifted is still lit from the front",
            starting.shadeAlpha < halfway.shadeAlpha,
        )
    }

    @Test
    fun `only the curl rotates the page`() {
        for (effect in listOf(PageTurnEffect.SLIDE, PageTurnEffect.FADE)) {
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
            pageTurnTransform(-1f, PageTurnEffect.CURL, isRtl = false),
            pageTurnTransform(-1.4f, PageTurnEffect.CURL, isRtl = false),
        )
        assertEquals(
            pageTurnTransform(1f, PageTurnEffect.FADE, isRtl = false),
            pageTurnTransform(1.4f, PageTurnEffect.FADE, isRtl = false),
        )
    }

    @Test
    fun `a curl's rotation grows with the distance turned`() {
        val quarter = pageTurnTransform(-0.25f, PageTurnEffect.CURL, isRtl = false)
        val half = pageTurnTransform(-0.5f, PageTurnEffect.CURL, isRtl = false)
        val whole = pageTurnTransform(-1f, PageTurnEffect.CURL, isRtl = false)

        assertTrue(quarter.rotationY > half.rotationY)
        assertTrue(half.rotationY > whole.rotationY)
    }
}
