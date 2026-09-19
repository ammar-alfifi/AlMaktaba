package com.mylibrary.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.usecase.NextBookInFolder
import com.mylibrary.core.ui.component.BookCover
import com.mylibrary.core.ui.theme.Spacing

/**
 * The key of the item a scroll reader puts after its last page or chapter.
 *
 * A string, and deliberately not the number a content item would be keyed by: the content items are
 * keyed by their index, so a footer keyed `totalUnits` would collide with a real page of a document
 * whose last index that is.
 */
internal const val NEXT_BOOK_ITEM_KEY = "next-book"

/**
 * The content index a column's [index] refers to, tolerating the next-book panel at the end of it.
 *
 * The panel is the one item in a scroll reader that is not part of the document, and it is last — so
 * a reader who has scrolled onto it is on the last *page*, not on nothing, and the position both
 * directions of the column's state sync report has to say so. Without this the reader would scroll
 * onto the panel, report a position past the end, have that clamped, and then be dragged back to the
 * last page by the effect that answers a changed position — making the panel impossible to reach.
 */
internal fun contentIndex(index: Int, unitCount: Int): Int =
    if (unitCount <= 0) 0 else index.coerceIn(0, unitCount - 1)

/**
 * The end of a book, and the beginning of the next one in the folder.
 *
 * Shown by both scroll readers after the last page or chapter, because a device folder is a series
 * and a reader who has just finished volume two wants volume three — the alternative is sending them
 * back to the shelf to find it, which is where the thread of a series is usually dropped.
 *
 * It is an *offer*, not an automatic move: the reader may want to go back, to look something up, or
 * simply to stop, and a reader that turned the page into another book on its own would take that
 * decision away. Mihon's chapter end is the same shape and for the same reason — a card that says
 * what comes next, and a button that goes there.
 *
 * What it shows is the *folder's* next volume rather than the library's: the series name is named
 * explicitly, so the offer is one the reader can recognise as theirs rather than an unexplained jump
 * to a book they may not have been thinking about.
 */
@Composable
internal fun NextBookFooter(
    next: NextBookInFolder,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = next.book

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.XLarge, bottom = Spacing.Huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Text(
            text = stringResource(R.string.reader_end_of_book),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Large),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(
                    book = book,
                    modifier = Modifier.width(52.dp),
                    contentDescription = null,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
                ) {
                    Text(
                        // Falls back to the plain label when the folder row is gone, which is the
                        // one case a filed book can have no folder name.
                        text = next.folderName?.let {
                            stringResource(R.string.reader_next_book_in_folder, it)
                        } ?: stringResource(R.string.reader_next_book),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.author?.takeIf { it.isNotBlank() }?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.Large, end = Spacing.Large, bottom = Spacing.Large),
            ) {
                Text(stringResource(com.mylibrary.core.ui.R.string.ui_continue_reading))
            }
        }
    }
}
