package com.mylibrary.feature.reader

import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the rule that decides which panel actions a document gets.
 *
 * This is the whole of "unified, but not identical": one toolbar for five formats, differing only in
 * what the open file can actually do. Each case below mirrors what the corresponding engine declares
 * in `EngineCapabilities`, so if an engine's capabilities change and the toolbar does not, these are
 * what notices.
 */
class ReaderToolbarTest {

    private fun stateWith(
        outline: List<TocEntry> = emptyList(),
        capabilities: EngineCapabilities = EngineCapabilities(),
    ) = ReaderUiState(outline = outline, capabilities = capabilities)

    private val outline = listOf(TocEntry(title = "الفصل الأول", locator = ReadingLocator.Reflowable(0, 0)))

    /** PDF: pdfium's text layer is real, and the outline follows the document's own bookmarks. */
    @Test
    fun `a PDF with bookmarks offers contents, search and bookmarks`() {
        val actions = readerMenuActions(
            stateWith(outline, EngineCapabilities(canSearch = true, canExtractText = true, canRenderPages = true, hasOutline = true)),
        )

        assertEquals(
            listOf(
                ReaderMenuAction.TableOfContents,
                ReaderMenuAction.Search,
                ReaderMenuAction.Bookmarks,
            ),
            actions,
        )
    }

    /** A PDF whose author never made bookmarks has no contents to show. */
    @Test
    fun `a PDF without bookmarks drops the contents action`() {
        val actions = readerMenuActions(
            stateWith(capabilities = EngineCapabilities(canSearch = true, canRenderPages = true)),
        )

        assertEquals(
            listOf(ReaderMenuAction.Search, ReaderMenuAction.Bookmarks),
            actions,
        )
    }

    /** EPUB: reflowable, searchable, and usually navigable. */
    @Test
    fun `an EPUB offers contents, search and bookmarks`() {
        val actions = readerMenuActions(
            stateWith(outline, EngineCapabilities(canSearch = true, canExtractText = true, hasOutline = true)),
        )

        assertEquals(
            listOf(
                ReaderMenuAction.TableOfContents,
                ReaderMenuAction.Search,
                ReaderMenuAction.Bookmarks,
            ),
            actions,
        )
    }

    /** TXT: one long stream of text, searchable, with no outline to navigate. */
    @Test
    fun `a plain text file offers search and bookmarks but no contents`() {
        val actions = readerMenuActions(
            stateWith(capabilities = EngineCapabilities(canSearch = true, canExtractText = true)),
        )

        assertEquals(
            listOf(ReaderMenuAction.Search, ReaderMenuAction.Bookmarks),
            actions,
        )
    }

    /**
     * CBZ/CBR: images, no text layer, no outline — so search and contents would both be buttons
     * whose only possible outcome is an apology.
     */
    @Test
    fun `a comic archive offers bookmarks alone`() {
        val actions = readerMenuActions(
            stateWith(capabilities = EngineCapabilities(canRenderPages = true)),
        )

        assertEquals(listOf(ReaderMenuAction.Bookmarks), actions)
    }

    /**
     * Bookmarking is not backed by an engine capability because it does not need one: a bookmark is
     * a position the reader stores itself, and every format has positions.
     */
    @Test
    fun `bookmarks are offered even for a document that reports nothing at all`() {
        assertEquals(listOf(ReaderMenuAction.Bookmarks), readerMenuActions(ReaderUiState()))
    }

    /**
     * An outline that is claimed but empty is the same situation as no outline, and the toolbar
     * reads the list rather than the flag so the two cannot disagree.
     */
    @Test
    fun `an empty outline is treated as no outline`() {
        val actions = readerMenuActions(
            stateWith(capabilities = EngineCapabilities(canSearch = true, hasOutline = true)),
        )

        assertEquals(listOf(ReaderMenuAction.Search, ReaderMenuAction.Bookmarks), actions)
    }

    /** Every action maps to the panel it opens, which is what the toolbar dispatches on. */
    @Test
    fun `each action names the panel it opens`() {
        assertEquals(ReaderPanel.TABLE_OF_CONTENTS, ReaderMenuAction.TableOfContents.panel)
        assertEquals(ReaderPanel.SEARCH, ReaderMenuAction.Search.panel)
        assertEquals(ReaderPanel.BOOKMARKS, ReaderMenuAction.Bookmarks.panel)
    }

    /** Bookmarks come last: it is the action reached for least often while reading. */
    @Test
    fun `bookmarks are always the last entry`() {
        val full = readerMenuActions(
            stateWith(outline, EngineCapabilities(canSearch = true, hasOutline = true)),
        )

        assertEquals(ReaderMenuAction.Bookmarks, full.last())
    }
}
