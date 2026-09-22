package com.mylibrary.core.ui.component

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the shape of one button in a two-option segmented row.
 *
 * The bug these exist for is directional, and it is the kind a screenshot of one language never
 * catches: the selected half of a segmented control was rounded against its neighbour and square at
 * the screen edge in Arabic while the English one was the other way round.
 *
 * **The corners are logical and are not mirrored here.** `RoundedCornerShape` resolves
 * `topStart`/`topEnd` for the direction it is drawn in — its own `createOutline` builds the
 * top-left corner from `topStart` in an LTR layout and from `topEnd` in an RTL one — so naming the
 * first button's rounding `Start` is what puts it on the outer edge of both an English and an
 * Arabic row. Mirroring the indices, as this used to, is what inverted the Arabic one.
 */
class ChoiceRowTest {

    private val rounded = CornerSize(16.dp)
    private val square = CornerSize(0.dp)
    private val base = RoundedCornerShape(16.dp)

    @Test
    fun `the first button is rounded at its start edge, whichever direction the row reads`() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            val shape = segmentedItemShape(0, 2, base, direction) as RoundedCornerShape

            assertEquals("$direction: outer corners rounded", rounded, shape.topStart)
            assertEquals(rounded, shape.bottomStart)
            assertEquals("$direction: the join is square", square, shape.topEnd)
            assertEquals(square, shape.bottomEnd)
        }
    }

    @Test
    fun `the last button is rounded at its end edge, whichever direction the row reads`() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            val shape = segmentedItemShape(1, 2, base, direction) as RoundedCornerShape

            assertEquals("$direction: the join is square", square, shape.topStart)
            assertEquals(square, shape.bottomStart)
            assertEquals("$direction: outer corners rounded", rounded, shape.topEnd)
            assertEquals(rounded, shape.bottomEnd)
        }
    }

    /** The regression itself: the two directions must produce the *same* logical shape. */
    @Test
    fun `the shape does not change with the row's direction`() {
        listOf(0, 1).forEach { index ->
            assertEquals(
                "button $index must not be mirrored for RTL",
                segmentedItemShape(index, 2, base, LayoutDirection.Ltr),
                segmentedItemShape(index, 2, base, LayoutDirection.Rtl),
            )
        }
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
