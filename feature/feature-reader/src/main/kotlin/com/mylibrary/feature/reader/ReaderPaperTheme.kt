package com.mylibrary.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mylibrary.core.domain.model.ReaderPaper

/**
 * Repaints the reading surface for a paper that is not the app's.
 *
 * The readers draw their pages and their ink through `MaterialTheme.colorScheme` — a reflowed page is
 * `background`, its text is `onSurface`, a page-image backdrop is `surface` — so overriding those
 * colours for the reader's content subtree is all it takes to give the page a paper of its own. It is
 * deliberately scoped to the content: the toolbar, the progress bar and every sheet stay on the app's
 * theme, because they are the interface rather than the page.
 *
 * [ReaderPaper.DEFAULT] is a no-op that leaves the ambient scheme untouched, so nothing changes for a
 * reader who never opens this control.
 */
@Composable
internal fun ReaderPaperSurface(paper: ReaderPaper, content: @Composable () -> Unit) {
    if (paper == ReaderPaper.DEFAULT) {
        content()
        return
    }

    val base = MaterialTheme.colorScheme
    val tint = paper.tint()
    MaterialTheme(
        colorScheme = base.copy(
            background = tint.background,
            onBackground = tint.onBackground,
            surface = tint.background,
            onSurface = tint.onBackground,
            surfaceVariant = tint.surfaceVariant,
            onSurfaceVariant = tint.onSurfaceVariant,
            surfaceContainer = tint.surfaceContainer,
            surfaceContainerHigh = tint.surfaceContainerHigh,
            outlineVariant = tint.outlineVariant,
        ),
        content = content,
    )
}

/** The colours one paper paints with. */
private data class PaperTint(
    val background: Color,
    val onBackground: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val outlineVariant: Color,
)

private fun ReaderPaper.tint(): PaperTint = when (this) {
    // Warm off-white paper with dark ink, and the greys around it pulled warm to match, so a divider
    // or a code block does not read as a cold rectangle dropped onto a warm page.
    ReaderPaper.SEPIA -> PaperTint(
        background = Color(0xFFF5ECD7),
        onBackground = Color(0xFF3B3327),
        surfaceVariant = Color(0xFFE9DEC3),
        onSurfaceVariant = Color(0xFF6B5E49),
        surfaceContainer = Color(0xFFEFE5CE),
        surfaceContainerHigh = Color(0xFFE9DEC3),
        outlineVariant = Color(0xFFD6C8A8),
    )

    // True black, with the ink dimmed rather than pure white so a dark room is not glaring.
    ReaderPaper.BLACK -> PaperTint(
        background = Color(0xFF000000),
        onBackground = Color(0xFFD0D0D0),
        surfaceVariant = Color(0xFF121212),
        onSurfaceVariant = Color(0xFF9A9A9A),
        surfaceContainer = Color(0xFF0A0A0A),
        surfaceContainerHigh = Color(0xFF141414),
        outlineVariant = Color(0xFF2A2A2A),
    )

    // Handled by the early return above; listed so the `when` stays exhaustive.
    ReaderPaper.DEFAULT -> error("DEFAULT is handled before a tint is asked for")
}
