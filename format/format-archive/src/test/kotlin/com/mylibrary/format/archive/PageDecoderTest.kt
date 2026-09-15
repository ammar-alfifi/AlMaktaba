package com.mylibrary.format.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The downsampling arithmetic, which is the difference between a comic reader that works and one
 * that dies with `OutOfMemoryError` on the second page turn.
 *
 * This is testable on a plain JVM because it is pure arithmetic: the risky part is *choosing* the
 * sample factor, while `BitmapFactory` merely applies it.
 */
class PageDecoderTest {

    /** Scans of the sizes that really show up in downloaded comics, and viewports to fit them in. */
    private val cases: List<IntArray> = listOf(
        // scan width, scan height, viewport width, viewport height
        intArrayOf(4000, 6000, 1080, 1920),
        intArrayOf(8000, 12000, 1080, 1920),
        intArrayOf(2000, 3000, 1080, 1920),
        intArrayOf(3000, 2000, 2400, 1080),
        intArrayOf(1600, 2560, 800, 1280),
        intArrayOf(600, 900, 1080, 1920),
    )

    @Test
    fun `never decodes below the requested viewport`() {
        cases.forEach { (width, height, targetWidth, targetHeight) ->
            val sample = PageDecoder.calculateInSampleSize(width, height, targetWidth, targetHeight)

            // Downsampling may only give away resolution the viewport does not need. A page smaller
            // than the viewport is the one exception: it is decoded whole, because there is nothing
            // to give away.
            assertThat(width / sample).isAtLeast(minOf(width, targetWidth))
            assertThat(height / sample).isAtLeast(minOf(height, targetHeight))
        }
    }

    @Test
    fun `never decodes the binding dimension at more than twice the viewport`() {
        cases.forEach { (width, height, targetWidth, targetHeight) ->
            val sample = PageDecoder.calculateInSampleSize(width, height, targetWidth, targetHeight)

            // The dimension that limits the choice is the one that would halve next: it therefore has
            // to be under twice its target, or halving once more would still have covered the
            // viewport and would have been chosen instead.
            val binding = minOf(width / sample.toDouble() / targetWidth, height / sample.toDouble() / targetHeight)

            assertThat(binding).isLessThan(2.0)
        }
    }

    @Test
    fun `reduces a large scan to the viewport's scale`() {
        // 8000x12000 is 384 MB as ARGB_8888 decoded whole, and 24 MB at 1/4 — the difference
        // between a heap overflow and a page turn.
        assertThat(PageDecoder.calculateInSampleSize(8000, 12000, 1080, 1920)).isEqualTo(4)
        assertThat(PageDecoder.calculateInSampleSize(4000, 6000, 1080, 1920)).isEqualTo(2)
    }

    @Test
    fun `decodes at full size when the page is smaller than the viewport`() {
        assertThat(PageDecoder.calculateInSampleSize(600, 900, 1080, 1920)).isEqualTo(1)
    }

    @Test
    fun `decodes at full size when the caller has no viewport yet`() {
        assertThat(PageDecoder.calculateInSampleSize(4000, 6000, targetWidthPx = 0, targetHeightPx = 0)).isEqualTo(1)
        assertThat(PageDecoder.calculateInSampleSize(4000, 6000, targetWidthPx = -1, targetHeightPx = 1080)).isEqualTo(1)
    }

    @Test
    fun `only ever returns powers of two, because that is all the platform applies`() {
        (1..16).forEach { power ->
            val width = 1000 * (1 shl power)

            val sample = PageDecoder.calculateInSampleSize(width, width, 1000, 1000)

            assertThat(sample).isEqualTo(1 shl power)
        }
    }
}
