package com.mylibrary.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mylibrary.core.ui.theme.Spacing

/**
 * The page between two books of a folder: what has just been finished, and what comes next.
 *
 * A folder is a series, and the reader carries on into the next volume rather than sending the
 * reader back to the shelf for it — so the seam is *crossed* rather than promised, in both
 * directions, and there is no page of the next book that pretends to be a page of this one. The
 * seam is what makes that honest: one page that belongs to neither book, saying which one ended and
 * which one is about to begin.
 *
 * **Why a page and not a card over the last page.** A card is something to read *instead of* what
 * comes next, and it has to be dismissed before the reader can go on; a page is something to turn
 * through. It also gives both directions the same shape, which is what a reader who has scrolled
 * backwards into a volume expects to find there: the same seam, read from the other side.
 *
 * It is deliberately empty otherwise. Nothing here is a control the reader has to understand: the
 * continuity is the feature, and a button in the middle of it would be a decision to make at the
 * one moment they are least likely to want one.
 *
 * [onOpenNext] is the exception, and it is for the case where the continuity could not happen: a
 * next volume waiting for a password, one whose file will not open, or one this reader draws with
 * different machinery. Rather than a dead end with an explanation, the seam offers to open it as a
 * book of its own — which is what the reader did before any of this existed. The book *behind* the
 * open one never gets that button: it is already behind them, and its saved position, not its first
 * page, is where they would want to arrive.
 */
@Composable
internal fun ReaderSeamPage(
    fromTitle: String,
    toTitle: String,
    onOpenNext: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // The page's own ground rather than the reader's: a seam is a page of the book, and a
            // gutter of a different colour between two pages of the same book would read as a
            // rendering fault.
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.XLarge, vertical = Spacing.Huge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            SeamLabel(text = stringResource(R.string.reader_seam_finished))
            SeamTitle(text = fromTitle)

            Column(
                modifier = Modifier.padding(top = Spacing.XLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                SeamLabel(text = stringResource(R.string.reader_seam_next))
                SeamTitle(text = toTitle)
            }

            onOpenNext?.let { open ->
                Button(
                    onClick = open,
                    modifier = Modifier.padding(top = Spacing.XLarge),
                ) {
                    Text(stringResource(com.mylibrary.core.ui.R.string.ui_continue_reading))
                }
            }
        }
    }
}

/** The small line that names what each title below it is. */
@Composable
private fun SeamLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** The name of a book at the seam, which is all the reader needs to recognise it. */
@Composable
private fun SeamTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
