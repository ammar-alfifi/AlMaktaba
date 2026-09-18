package com.mylibrary.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the tap-zone mapping.
 *
 * The mirroring is the reason these exist. Reading direction is the app's default language, so a
 * left/right swap here is not a cosmetic slip — it turns the page backwards for every Arabic reader,
 * and it does so silently, because a page that goes the wrong way still looks like a page turning.
 */
class ReaderGesturesTest {

    private val width = 900f

    private fun zoneAt(fraction: Float, isRtl: Boolean = false, reversed: Boolean = false) =
        tapZoneFor(x = width * fraction, width = width, isRtl = isRtl, reversed = reversed)

    // region left to right

    @Test
    fun `in a left-to-right document the left edge goes back and the right edge goes on`() {
        assertEquals(TapZone.PREVIOUS, zoneAt(0.1f))
        assertEquals(TapZone.CENTER, zoneAt(0.5f))
        assertEquals(TapZone.NEXT, zoneAt(0.9f))
    }

    // endregion

    // region right to left

    @Test
    fun `in a right-to-left document the zones are mirrored`() {
        assertEquals(TapZone.NEXT, zoneAt(0.1f, isRtl = true))
        assertEquals(TapZone.CENTER, zoneAt(0.5f, isRtl = true))
        assertEquals(TapZone.PREVIOUS, zoneAt(0.9f, isRtl = true))
    }

    /** The physical gesture is what is being kept consistent, so the zones swap and the middle does not. */
    @Test
    fun `direction never moves the middle zone`() {
        assertEquals(TapZone.CENTER, zoneAt(0.5f))
        assertEquals(TapZone.CENTER, zoneAt(0.5f, isRtl = true))
    }

    // endregion

    // region reversal

    @Test
    fun `reversing swaps the two edges in a left-to-right document`() {
        assertEquals(TapZone.NEXT, zoneAt(0.1f, reversed = true))
        assertEquals(TapZone.PREVIOUS, zoneAt(0.9f, reversed = true))
    }

    /**
     * The whole point of the setting, and the reason it is combined with the mirroring by exclusive
     * or rather than applied on top of it: an Arabic book reads right to left, and this is how a
     * reader keeps the book the way the book goes while their thumb keeps the habit it already has.
     * If this ever returned the mirrored answer instead, the setting would do nothing at all for the
     * readers most likely to reach for it.
     */
    @Test
    fun `reversing undoes the mirroring a right-to-left document applied`() {
        assertEquals(TapZone.PREVIOUS, zoneAt(0.1f, isRtl = true, reversed = true))
        assertEquals(TapZone.NEXT, zoneAt(0.9f, isRtl = true, reversed = true))
    }

    /** Asked twice, the same question can only give the answer back — so the two cancel, not stack. */
    @Test
    fun `a reversed right-to-left surface matches an unreversed left-to-right one`() {
        for (fraction in listOf(0.1f, 0.29f, 0.5f, 0.71f, 0.9f)) {
            assertEquals(
                "at $fraction of the width",
                zoneAt(fraction),
                zoneAt(fraction, isRtl = true, reversed = true),
            )
        }
    }

    @Test
    fun `reversing never moves the middle zone`() {
        assertEquals(TapZone.CENTER, zoneAt(0.5f, reversed = true))
        assertEquals(TapZone.CENTER, zoneAt(0.5f, isRtl = true, reversed = true))
        assertEquals(TapZone.CENTER, tapZoneFor(x = 0f, width = 0f, isRtl = true, reversed = true))
    }

    // endregion

    // region boundaries

    @Test
    fun `the zone boundaries are exclusive at the outer edge and inclusive inside`() {
        // Just inside an edge zone.
        assertEquals(TapZone.PREVIOUS, zoneAt(0.29f))
        assertEquals(TapZone.NEXT, zoneAt(0.71f))

        // The boundary itself belongs to the middle, so the toolbar gesture cannot be shaved away
        // by a one-pixel rounding difference between two screens.
        assertEquals(TapZone.CENTER, zoneAt(0.3f))
        assertEquals(TapZone.CENTER, zoneAt(0.7f))
    }

    @Test
    fun `the exact corners are reached`() {
        assertEquals(TapZone.PREVIOUS, tapZoneFor(x = 0f, width = width, isRtl = false))
        assertEquals(TapZone.NEXT, tapZoneFor(x = width, width = width, isRtl = false))
    }

    /**
     * A tap reported outside the surface — which a gesture detector can produce while a layout is
     * changing size under it — is clamped rather than mapped to nonsense.
     */
    @Test
    fun `taps outside the surface clamp to the nearest edge`() {
        assertEquals(TapZone.PREVIOUS, tapZoneFor(x = -50f, width = width, isRtl = false))
        assertEquals(TapZone.NEXT, tapZoneFor(x = width + 50f, width = width, isRtl = false))
    }

    /** Before the first layout the surface has no width; a tap then must not turn the page. */
    @Test
    fun `a surface with no width reports the middle`() {
        assertEquals(TapZone.CENTER, tapZoneFor(x = 0f, width = 0f, isRtl = false))
        assertEquals(TapZone.CENTER, tapZoneFor(x = 10f, width = -1f, isRtl = true))
    }

    // endregion
}
