package com.mylibrary.feature.reader

import com.mylibrary.core.domain.model.ReaderLayout
import com.mylibrary.core.domain.model.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule the settings sheet is built on: a control appears where it is honoured.
 *
 * The sheet used to ask whether the *file* was made of page images, which is a different question
 * from whether the reader is turning pages — and answering the wrong one hid the page-turn control
 * from a paged EPUB that was honouring it. These four cases are the whole of the answer, and the
 * third is the bug.
 */
class ReaderSettingsScopeTest {

    private fun scopeOf(
        pageImages: Boolean,
        layout: ReaderLayout,
    ) = readerSettingsScope(
        ReaderUiState(
            isPageImages = pageImages,
            settings = ReaderSettings(layout = layout),
        ),
    )

    @Test
    fun `a comic in pages offers everything a page image can do`() {
        val scope = scopeOf(pageImages = true, layout = ReaderLayout.PAGED)
        assertFalse("a comic has no reflowed text", scope.text)
        assertTrue(scope.pages)
        assertTrue(scope.pageFit)
        assertTrue(scope.panels)
    }

    @Test
    fun `a comic in a continuous scroll is a column, not a stack of pages`() {
        val scope = scopeOf(pageImages = true, layout = ReaderLayout.SCROLL)
        assertFalse(scope.text)
        // Nothing to turn and nothing to fit: page fit describes a page in a viewport, and in a
        // column the page is simply as wide as the screen.
        assertFalse("a column has no pages to turn", scope.pages)
        assertFalse("nor a frame to fit a page into", scope.pageFit)
        // But a speech bubble is still unreadable at page width, and the scroll layout still opens
        // one at full size — so this control stays, and hiding it would be the same bug as the
        // page-turn control it took an emulator run to find.
        assertTrue("a scrolling comic still frames speech bubbles", scope.panels)
    }

    @Test
    fun `paged text is offered the page-turn effect`() {
        // The bug this replaced: an EPUB laid out as pages reads `pageTurnEffect` and animates its
        // turns with it, and was never offered the control because it is not a PDF.
        val scope = scopeOf(pageImages = false, layout = ReaderLayout.PAGED)
        assertTrue(scope.text)
        assertTrue("a paged EPUB turns pages and can choose how", scope.pages)
        assertFalse("but it has no page images to fit or frame", scope.pageFit)
        assertFalse(scope.panels)
    }

    @Test
    fun `scrolling text is offered neither`() {
        val scope = scopeOf(pageImages = false, layout = ReaderLayout.SCROLL)
        assertTrue(scope.text)
        assertFalse(scope.pages)
        assertFalse(scope.pageFit)
        assertFalse(scope.panels)
    }

    @Test
    fun `reflowed text is never offered a fit or a bubble`() {
        // Whatever the layout, controls that describe a *page* belong to the family that has them. A
        // reflowed page is measured to fit its text, not scaled to fit a frame — and it has no
        // panels to frame.
        ReaderLayout.entries.forEach { layout ->
            val scope = scopeOf(pageImages = false, layout = layout)
            assertFalse(scope.pageFit)
            assertFalse(scope.panels)
        }
    }

    @Test
    fun `page fit and bubble zoom do not gate on the same thing`() {
        // They look like a pair and are not: a page in a column has no frame to be fitted into, and
        // still has speech bubbles too small to read.
        val scrollingComic = scopeOf(pageImages = true, layout = ReaderLayout.SCROLL)
        assertFalse(scrollingComic.pageFit)
        assertTrue(scrollingComic.panels)
    }

    @Test
    fun `hasPages follows the layout, not the file`() {
        assertEquals(
            true,
            ReaderUiState(
                isPageImages = false,
                settings = ReaderSettings(layout = ReaderLayout.PAGED),
            ).hasPages,
        )
        assertEquals(
            false,
            ReaderUiState(
                isPageImages = true,
                settings = ReaderSettings(layout = ReaderLayout.SCROLL),
            ).hasPages,
        )
    }
}
