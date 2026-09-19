package com.mylibrary.feature.reader

import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two lengths the reading panel writes into the text, and what the settings do to them.
 *
 * Both are read through the state rather than passed down, because the two readers need the same
 * number for different purposes: the scrolling one pads its column with the margin, the paged one
 * subtracts twice it from the width it paginates into. Those two agreeing is what the single
 * accessor is for, and these tests hold the other half of it — that the settings reach it at all. A
 * reader whose margin slider moved the padding but not the pagination would be laid out against a
 * column that does not exist, and nothing else in the suite would notice.
 *
 * The base lengths are written as literals rather than read from the reader's own constants. That is
 * deliberate: the shipped column is a design decision, and moving it should take an edit here as
 * well as there.
 */
class ReaderTextScaleTest {

    private fun state(
        marginScale: Float = 1f,
        paragraphSpacingScale: Float = 1f,
    ) = ReaderUiState(
        settings = ReaderSettings(
            marginScale = marginScale,
            paragraphSpacingScale = paragraphSpacingScale,
        ),
    )

    @Test
    fun `the margins follow the setting, in both directions`() {
        assertEquals("the setting's 1.0 is the margin the reader shipped with", 20.dp, state().readingMargin())
        assertEquals("and twice it is twice the white on each side", 40.dp, state(marginScale = 2f).readingMargin())
        assertEquals(10.dp, state(marginScale = 0.5f).readingMargin())
    }

    @Test
    fun `paragraph spacing follows the setting`() {
        assertEquals(10.dp, state().paragraphSpacing())
        assertEquals(25.dp, state(paragraphSpacingScale = 2.5f).paragraphSpacing())
    }

    /**
     * The one value the panel can produce that is not a scale at all.
     *
     * Zero has to mean *no* gap rather than a small one: it is the setting a document that separates
     * its own blocks wants, and a base length added unconditionally would leave that reader with a
     * gap they had explicitly asked to be rid of. The margin's floor is not zero for the opposite
     * reason — text against the edge of the display is unreadable — so this is the single place the
     * two differ, and worth saying out loud.
     */
    @Test
    fun `the paragraph spacing slider's floor removes the gap entirely`() {
        assertEquals(0.dp, state(paragraphSpacingScale = 0f).paragraphSpacing())
    }
}
