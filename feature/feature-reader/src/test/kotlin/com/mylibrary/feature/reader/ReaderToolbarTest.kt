package com.mylibrary.feature.reader

import com.mylibrary.core.domain.model.EngineCapabilities
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the rule that decides which actions a document gets in its overflow.
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

    /** PDF: pdfium's text layer is real, so it can be searched and read aloud, and it has bookmarks. */
    @Test
    fun `a PDF with bookmarks offers contents, search, read aloud and bookmarks`() {
        val actions = readerMenuActions(
            stateWith(outline, EngineCapabilities(canSearch = true, canExtractText = true, canRenderPages = true, hasOutline = true)),
        )

        assertEquals(
            listOf(
                ReaderMenuAction.TableOfContents,
                ReaderMenuAction.Search,
                ReaderMenuAction.ReadAloud,
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
            listOf(ReaderMenuAction.Search, ReaderMenuAction.ReadAloud, ReaderMenuAction.Bookmarks),
            actions,
        )
    }

    /** EPUB: reflowable, searchable and speakable, and usually navigable. */
    @Test
    fun `an EPUB offers contents, search, read aloud and bookmarks`() {
        val actions = readerMenuActions(
            stateWith(outline, EngineCapabilities(canSearch = true, canExtractText = true, hasOutline = true)),
        )

        assertEquals(
            listOf(
                ReaderMenuAction.TableOfContents,
                ReaderMenuAction.Search,
                ReaderMenuAction.ReadAloud,
                ReaderMenuAction.Bookmarks,
            ),
            actions,
        )
    }

    /** TXT: one long stream of text, searchable and speakable, with no outline to navigate. */
    @Test
    fun `a plain text file offers search, read aloud and bookmarks but no contents`() {
        val actions = readerMenuActions(
            stateWith(capabilities = EngineCapabilities(canSearch = true, canExtractText = true)),
        )

        assertEquals(
            listOf(ReaderMenuAction.Search, ReaderMenuAction.ReadAloud, ReaderMenuAction.Bookmarks),
            actions,
        )
    }

    /**
     * CBZ/CBR: images, no text layer, no outline — so search, read aloud and contents would all be
     * buttons whose only possible outcome is an apology.
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

        assertEquals(
            listOf(ReaderMenuAction.Search, ReaderMenuAction.ReadAloud, ReaderMenuAction.Bookmarks),
            actions,
        )
    }

    /**
     * Each panel action names the panel it opens; read-aloud, which speaks in place, names none.
     * That null is what the toolbar dispatches on to decide between opening a sheet and speaking.
     */
    @Test
    fun `each panel action names its panel, and read aloud names none`() {
        assertEquals(ReaderPanel.TABLE_OF_CONTENTS, ReaderMenuAction.TableOfContents.panel)
        assertEquals(ReaderPanel.SEARCH, ReaderMenuAction.Search.panel)
        assertNull(ReaderMenuAction.ReadAloud.panel)
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
