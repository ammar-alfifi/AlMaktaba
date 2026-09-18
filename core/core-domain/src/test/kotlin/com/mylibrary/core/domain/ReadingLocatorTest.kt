package com.mylibrary.core.domain

import com.mylibrary.core.domain.model.ReadingLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the navigation-argument encoding of [ReadingLocator].
 *
 * The round trip matters beyond symmetry: a locator rides through the back stack as this string,
 * so `parse(encoded())` must hold for every locator the app can produce, and anything else — a
 * truncated value, a stale argument from an older build, a hand-edited deep link — must degrade to
 * `null` rather than throw, because the reader falls back to the saved position on `null`.
 */
class ReadingLocatorTest {

    @Test
    fun `paged locator round-trips`() {
        val locator = ReadingLocator.Paged(12)
        assertEquals("p12", locator.encoded())
        assertEquals(locator, ReadingLocator.parse("p12"))
    }

    @Test
    fun `reflowable locator round-trips`() {
        val locator = ReadingLocator.Reflowable(3, 450)
        assertEquals("r3:450", locator.encoded())
        assertEquals(locator, ReadingLocator.parse("r3:450"))
    }

    @Test
    fun `page zero and chapter zero round-trip`() {
        assertEquals(ReadingLocator.Paged(0), ReadingLocator.parse("p0"))
        assertEquals(ReadingLocator.Reflowable(0, 0), ReadingLocator.parse("r0:0"))
    }

    @Test
    fun `malformed strings parse to null rather than throwing`() {
        assertNull(ReadingLocator.parse(""))
        assertNull(ReadingLocator.parse("x1"))
        assertNull(ReadingLocator.parse("p"))
        assertNull(ReadingLocator.parse("p-1"))
        assertNull(ReadingLocator.parse("r3"))
        assertNull(ReadingLocator.parse("r3:"))
        assertNull(ReadingLocator.parse("r3:450:9"))
        assertNull(ReadingLocator.parse("r:-1:0"))
        assertNull(ReadingLocator.parse("page12"))
    }
}
