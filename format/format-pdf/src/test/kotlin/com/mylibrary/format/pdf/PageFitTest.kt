package com.mylibrary.format.pdf

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mylibrary.core.domain.model.PageSize
import org.junit.Test
import kotlin.math.abs

class PageFitTest {

    @Test
    fun `a portrait page in a square box is limited by its height`() {
        // A5, 595x842 points, into a 1000x1000 viewport.
        val fitted = fitPageInBox(pageWidth = 595, pageHeight = 842, boxWidth = 1000, boxHeight = 1000)

        assertThat(fitted).isEqualTo(PageSize(width = 707, height = 1000))
    }

    @Test
    fun `a landscape page in a square box is limited by its width`() {
        val fitted = fitPageInBox(pageWidth = 842, pageHeight = 595, boxWidth = 1000, boxHeight = 1000)

        assertThat(fitted).isEqualTo(PageSize(width = 1000, height = 707))
    }

    @Test
    fun `a page smaller than the box is scaled up to fill it`() {
        // The box is the viewport the reader wants filled, not a cap on the page's own size, so even
        // a low-resolution page is drawn across the whole viewport.
        val fitted = fitPageInBox(pageWidth = 100, pageHeight = 200, boxWidth = 1000, boxHeight = 1000)

        assertThat(fitted).isEqualTo(PageSize(width = 500, height = 1000))
    }

    @Test
    fun `a square page in a square box fills it exactly`() {
        val fitted = fitPageInBox(pageWidth = 612, pageHeight = 612, boxWidth = 512, boxHeight = 512)

        assertThat(fitted).isEqualTo(PageSize(width = 512, height = 512))
    }

    @Test
    fun `a sliver never collapses to zero pixels`() {
        // A 1000:1 page is a broken page, but a bitmap with a zero dimension cannot be allocated at
        // all, so the fit floors at one pixel instead of returning a degenerate size.
        val fitted = fitPageInBox(pageWidth = 1000, pageHeight = 1, boxWidth = 100, boxHeight = 100)

        assertThat(fitted).isEqualTo(PageSize(width = 100, height = 1))
    }

    @Test
    fun `no area on either side yields no render target`() {
        assertThat(fitPageInBox(pageWidth = 0, pageHeight = 100, boxWidth = 100, boxHeight = 100))
            .isEqualTo(PageSize(0, 0))
        assertThat(fitPageInBox(pageWidth = 100, pageHeight = -1, boxWidth = 100, boxHeight = 100))
            .isEqualTo(PageSize(0, 0))
        assertThat(fitPageInBox(pageWidth = 100, pageHeight = 100, boxWidth = 0, boxHeight = 100))
            .isEqualTo(PageSize(0, 0))
        assertThat(fitPageInBox(pageWidth = 100, pageHeight = 100, boxWidth = 100, boxHeight = -5))
            .isEqualTo(PageSize(0, 0))
    }

    /**
     * The size is used to allocate a bitmap directly, so a result outside the box over-allocates and
     * a zero dimension throws. Swept over dimensions chosen to be awkward to round.
     */
    @Test
    fun `every fit stays inside the box and keeps a usable size`() {
        val dimensions = listOf(1, 2, 3, 7, 17, 595, 842, 1000, 4096)

        for (pageWidth in dimensions) {
            for (pageHeight in dimensions) {
                for (boxWidth in dimensions) {
                    for (boxHeight in dimensions) {
                        val fitted = fitPageInBox(pageWidth, pageHeight, boxWidth, boxHeight)
                        val label = "$pageWidth x $pageHeight into $boxWidth x $boxHeight"

                        assertWithMessage("width of $label").that(fitted.width).isAtLeast(1)
                        assertWithMessage("height of $label").that(fitted.height).isAtLeast(1)
                        assertWithMessage("width of $label").that(fitted.width).isAtMost(boxWidth)
                        assertWithMessage("height of $label").that(fitted.height).isAtMost(boxHeight)
                    }
                }
            }
        }
    }

    /**
     * Aspect ratio is the point of the fit — pdfium stretches a page onto whatever rectangle it is
     * given, so a ratio that drifts here is a visibly skewed page. Checked within one pixel of the
     * width axis, which is all the per-axis rounding can account for.
     */
    @Test
    fun `the aspect ratio is preserved within a pixel`() {
        val cases = listOf(
            // pageWidth, pageHeight, boxWidth, boxHeight
            listOf(595, 842, 1080, 1920),
            listOf(595, 842, 1000, 1000),
            listOf(842, 595, 1000, 1000),
            listOf(612, 792, 1080, 1920),
            listOf(300, 400, 333, 999),
            listOf(1000, 2000, 640, 480),
            listOf(1240, 1754, 720, 1280),
        )

        for ((pageWidth, pageHeight, boxWidth, boxHeight) in cases) {
            val fitted = fitPageInBox(pageWidth, pageHeight, boxWidth, boxHeight)
            val label = "$pageWidth x $pageHeight into $boxWidth x $boxHeight"

            val pageRatio = pageWidth.toDouble() / pageHeight
            val fittedRatio = fitted.width.toDouble() / fitted.height

            assertWithMessage("aspect ratio of $label")
                .that(abs(fittedRatio - pageRatio))
                .isLessThan(1.0 / fitted.height)
        }
    }
}
