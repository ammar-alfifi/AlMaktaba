package com.mylibrary.core.ui.component

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for the shape of one button in a two-option segmented row.
 *
 * The bug these exist for is directional, and it is the kind that never shows up in a screenshot of
 * one language: the selected fill of a segmented control was mirrored in the English interface, so
 * its rounding was reversed — square where the outline is round and round where the outline is
 * square. The shape is therefore built from the row's own direction here, and the mirroring is
 * asserted in both directions rather than left to the library's own resolution.
 *
 * The rounding is always placed on the *outside* of the row: `Start` in a left-to-right row,
 * `End` in a right-to-left one, so that `RoundedCornerShape`'s own swap lands it on the row's outer
 * edge. The assertions below are in those terms because that is the contract.
 */
class ChoiceRowTest {

    private val rounded = CornerSize(16.dp)
    private val square = CornerSize(0.dp)
    private val base = RoundedCornerShape(16.dp)

    @Test
    fun `in a left-to-right row the first button is rounded on the left`() {
        val shape = segmentedItemShape(0, 2, base, LayoutDirection.Ltr) as RoundedCornerShape

        assertEquals("outer corners rounded", rounded, shape.topStart)
        assertEquals(rounded, shape.bottomStart)
        assertEquals("the join is square", square, shape.topEnd)
        assertEquals(square, shape.bottomEnd)
    }

    @Test
    fun `in a left-to-right row the last button is rounded on the right`() {
        val shape = segmentedItemShape(1, 2, base, LayoutDirection.Ltr) as RoundedCornerShape

        assertEquals(square, shape.topStart)
        assertEquals(square, shape.bottomStart)
        assertEquals("outer corners rounded", rounded, shape.topEnd)
        assertEquals(rounded, shape.bottomEnd)
    }

    /** The mirror is the whole point: the first button of an RTL row is rounded on the other edge. */
    @Test
    fun `a right-to-left row mirrors the rounding`() {
        val ltr = segmentedItemShape(0, 2, base, LayoutDirection.Ltr) as RoundedCornerShape
        val rtl = segmentedItemShape(0, 2, base, LayoutDirection.Rtl) as RoundedCornerShape

        assertEquals("the rounded edge moves to the other side", ltr.topStart, rtl.topEnd)
        assertEquals(ltr.bottomStart, rtl.bottomEnd)
        assertEquals("and the join is square where it was round", ltr.topEnd, rtl.topStart)
        assertEquals(ltr.bottomEnd, rtl.bottomStart)
        assertNotEquals("and it is genuinely not the same shape", ltr, rtl)
    }

    @Test
    fun `a middle button is square on every corner`() {
        val shape = segmentedItemShape(1, 3, base, LayoutDirection.Ltr) as RoundedCornerShape

        assertEquals(square, shape.topStart)
        assertEquals(square, shape.topEnd)
        assertEquals(square, shape.bottomStart)
        assertEquals(square, shape.bottomEnd)
    }

    @Test
    fun `a lone button keeps the whole base shape`() {
        assertEquals(base, segmentedItemShape(0, 1, base, LayoutDirection.Ltr))
        assertEquals(base, segmentedItemShape(0, 1, base, LayoutDirection.Rtl))
    }
}
