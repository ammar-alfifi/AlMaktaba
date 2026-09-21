package com.mylibrary.feature.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rendered-page cache.
 *
 * The class is small enough to look obviously right, which is exactly why the bug it carried went
 * unnoticed for three releases: eviction was split between `LinkedHashMap.removeEldestEntry`, which
 * fires *inside* the map's own insertion, and a trim loop that ran afterwards — and only the trim
 * loop decremented the running byte count. Every entry the map dropped on its own therefore left its
 * bytes behind in the counter, and the cache reported a full budget while holding a fraction of one.
 *
 * The first test below is the one that catches that, and it catches it by asking the only question
 * that cannot be answered by reading either half of the eviction: does the number the cache reports
 * match the pages it can still hand back.
 */
class PageCacheTest {

    private val width = 100
    private val height = 100

    /** One page's worth of pixels as the cache counts them: `width * height * 4`. */
    private val pageBytes = width * height * 4

    private fun keyFor(pageIndex: Int, documentId: String = "test://book") = PageCache.Key(
        documentId = documentId,
        pageIndex = pageIndex,
        widthPx = width,
        heightPx = height,
        backgroundColorArgb = 0xFFFFFFFF.toInt(),
    )

    // region accounting

    /**
     * The regression. A budget that fits more pages than the entry ceiling means the map evicts on
     * its own, which is the path that used to leak the counter — so this is the configuration where
     * the two halves of eviction had to agree, and did not.
     */
    @Test
    fun `the reported byte count matches the pages actually held`() {
        // Room for forty pages, but at most twenty-four are ever kept.
        val cache = PageCache(maxBytes = 40 * pageBytes)
        val keys = (0 until 60).map { keyFor(it) }

        keys.forEach { cache.put(it, image()) }

        val resident = keys.count { cache.get(it) != null }
        assertEquals("the entry ceiling is what binds here", MAX_ENTRIES, resident)
        assertEquals(
            "the counter must agree with what is resident",
            (resident * pageBytes).toLong(),
            cache.sizeBytes.toLong(),
        )
    }

    @Test
    fun `a page larger than the whole budget is refused rather than emptying the cache`() {
        val cache = PageCache(maxBytes = 4 * pageBytes)
        (0 until 4).forEach { cache.put(keyFor(it), image()) }

        cache.put(keyFor(99), image(side = width * 4))

        assertEquals("the four small pages must survive", 4, cache.count)
        assertNull("and the oversized one must not be stored", cache.get(keyFor(99)))
    }

    @Test
    fun `the budget is honoured as pages accumulate`() {
        val cache = PageCache(maxBytes = 4 * pageBytes)

        (0 until 100).forEach { cache.put(keyFor(it), image()) }

        assertTrue("over budget at ${cache.sizeBytes}", cache.sizeBytes <= 4 * pageBytes)
        assertEquals("a full budget's worth is still held", 4, cache.count)
    }

    // endregion

    // region eviction order

    @Test
    fun `the least recently used page is the one dropped`() {
        val cache = PageCache(maxBytes = 3 * pageBytes)
        cache.put(keyFor(0), image())
        cache.put(keyFor(1), image())
        cache.put(keyFor(2), image())

        // Reading page 0 makes page 1 the least recently used.
        assertNotNull(cache.get(keyFor(0)))
        cache.put(keyFor(3), image())

        assertNull("page 1 was the coldest", cache.get(keyFor(1)))
        assertNotNull("page 0 was just read", cache.get(keyFor(0)))
        assertNotNull(cache.get(keyFor(3)))
    }

    @Test
    fun `an absent page is absent, not a miss that evicts something`() {
        val cache = PageCache(maxBytes = 2 * pageBytes)
        cache.put(keyFor(0), image())

        assertNull(cache.get(keyFor(7)))

        assertEquals(1, cache.count)
    }

    /** A page re-rendered at the same box replaces its entry rather than being counted twice. */
    @Test
    fun `replacing a page keeps the count honest`() {
        val cache = PageCache(maxBytes = 4 * pageBytes)
        cache.put(keyFor(0), image())
        cache.put(keyFor(1), image())

        cache.put(keyFor(0), image())

        assertEquals(2, cache.count)
        assertEquals((2 * pageBytes).toLong(), cache.sizeBytes.toLong())
    }

    @Test
    fun `clearing drops every page and the whole count`() {
        val cache = PageCache(maxBytes = 4 * pageBytes)
        (0 until 4).forEach { cache.put(keyFor(it), image()) }

        cache.clear()

        assertEquals(0, cache.count)
        assertEquals(0, cache.sizeBytes)
        assertNull(cache.get(keyFor(0)))
    }

    // endregion

    // region one document of several

    /**
     * Closing a volume of a series gives up that volume's pages, and only those.
     *
     * The reader holds the book on either side of the open one, so the cache does too. Both halves of
     * that are worth pinning: pages of the book the reader is reading must survive a neighbour being
     * closed — dropping them would re-render every page on screen — and the bytes of the pages that
     * did go must leave the count with them, which is the accounting mistake this class has already
     * made once.
     */
    @Test
    fun `closing a document takes its pages and no others`() {
        val cache = PageCache(maxBytes = 8 * pageBytes)
        cache.put(keyFor(0, "test://volume-1"), image())
        cache.put(keyFor(1, "test://volume-1"), image())
        cache.put(keyFor(0, "test://volume-2"), image())

        cache.evict("test://volume-1")

        assertNull(cache.get(keyFor(0, "test://volume-1")))
        assertNull(cache.get(keyFor(1, "test://volume-1")))
        assertNotNull("the open book keeps its page", cache.get(keyFor(0, "test://volume-2")))
        assertEquals(pageBytes.toLong(), cache.sizeBytes.toLong())
    }

    @Test
    fun `evicting a document that holds nothing leaves the rest of the cache alone`() {
        val cache = PageCache(maxBytes = 4 * pageBytes)
        cache.put(keyFor(0), image())
        cache.put(keyFor(1), image())

        cache.evict("test://not-open")

        assertEquals(2, cache.count)
        assertEquals((2 * pageBytes).toLong(), cache.sizeBytes.toLong())
    }

    // endregion

    /**
     * A stand-in for a decoded page.
     *
     * [ImageBitmap] is an interface, so this needs no `android.graphics.Bitmap` and no Robolectric:
     * the cache only ever reads `width` and `height` off what it is handed, and a test double that
     * returns those is a complete description of a page as far as this class is concerned.
     */
    private fun image(side: Int = width): ImageBitmap = FakeImage(side, side)

    private class FakeImage(
        override val width: Int,
        override val height: Int,
    ) : ImageBitmap {
        override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override val hasAlpha: Boolean get() = false

        override fun prepareToDraw() = Unit

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit
    }

    private companion object {
        /** The cache's own ceiling on how many pages it will hold, whatever the byte budget says. */
        const val MAX_ENTRIES = 24
    }
}
