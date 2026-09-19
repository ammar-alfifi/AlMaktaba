package com.mylibrary.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.ProgressScope

/**
 * A panel the toolbar can open, in the order the overflow lists them.
 *
 * The reader serves five formats through four engines, and they do not all have the same parts: a
 * comic archive has no text layer to search and no outline to navigate, and a plain-text file has no
 * outline either. An action is therefore *derived from what the document can do* rather than assumed,
 * which is what lets one toolbar serve every format without offering buttons that quietly do nothing.
 */
enum class ReaderMenuAction(val panel: ReaderPanel) {
    TableOfContents(ReaderPanel.TABLE_OF_CONTENTS),
    Search(ReaderPanel.SEARCH),
    Bookmarks(ReaderPanel.BOOKMARKS),
}

/**
 * The panel actions that make sense for [state]'s document, in display order.
 *
 * Pure and free of Compose so it can be asserted directly in a JVM test — this is the rule that
 * decides what "unified, but not identical" means for each of the five formats, and it is worth
 * being able to pin down exactly:
 *
 *  - **Table of contents** only when the document declares one. Offering it for a file with no
 *    outline means a button whose only outcome is an apology.
 *  - **Search** only when the engine reports a text layer. A comic's pages are pictures; there is
 *    nothing to find in them.
 *  - **Bookmarks** always: every format can remember a position, including a page image.
 *
 * The bookmark *toggle* is not here: it is a stateful control that lives in the toolbar itself on
 * every format, not a panel.
 */
fun readerMenuActions(state: ReaderUiState): List<ReaderMenuAction> = buildList {
    if (state.outline.isNotEmpty()) add(ReaderMenuAction.TableOfContents)
    if (state.capabilities.canSearch) add(ReaderMenuAction.Search)
    add(ReaderMenuAction.Bookmarks)
}

/**
 * The reader's toolbar: where you are, and everything you can do from here.
 *
 * **One toolbar for all five formats.** The spine is fixed — back, title and position, bookmark
 * toggle, reading settings, overflow — so the bar looks the same whether a PDF, an EPUB, a text file
 * or a comic is open, and a user who has learned it in one format has learned it in all of them.
 * What varies is only the overflow's contents, which [readerMenuActions] derives from the document's
 * own capabilities.
 *
 * The panel actions live behind an overflow rather than spread along the bar for two reasons that
 * both come down to the same thing: five icon buttons plus a title do not fit a phone in either
 * language — Arabic titles are long — and a row of six unlabelled glyphs is a memory test. The menu
 * shows each action's icon *and* its name, so it teaches itself.
 *
 * Bookmark and settings stay inline because they are the two things done *while* reading a page;
 * the rest are all ways of leaving it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onBack: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val menuActions = readerMenuActions(state)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(com.mylibrary.core.ui.R.string.ui_close),
                    )
                }
            },
            title = {
                Column {
                    Text(
                        text = state.book?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.totalUnits > 0) {
                        Text(
                            text = state.positionDescription(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = {
                // Shown only after a link was followed, and inline rather than in the menu because
                // it is both urgent and transient: a footnote is otherwise a one-way trip to the
                // back of the book, and the way back should not cost a second tap to find.
                if (state.canReturnFromLink) {
                    IconButton(onClick = { onIntent(ReaderIntent.ReturnFromLink) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.reader_return_from_link),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                IconButton(onClick = { onIntent(ReaderIntent.ToggleBookmark) }) {
                    Icon(
                        imageVector = if (state.isBookmarked) {
                            Icons.Filled.Bookmark
                        } else {
                            Icons.Filled.BookmarkBorder
                        },
                        contentDescription = stringResource(R.string.reader_bookmark),
                        tint = if (state.isBookmarked) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                IconButton(onClick = { onIntent(ReaderIntent.OpenPanel(ReaderPanel.SETTINGS)) }) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.reader_settings),
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(
                                com.mylibrary.core.ui.R.string.ui_more,
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        menuActions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(readerMenuActionLabel(action)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = readerMenuActionIcon(action),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onIntent(ReaderIntent.OpenPanel(action.panel))
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

/**
 * What the toolbar says about where the reader is.
 *
 * A reflowable book has no pages of its own — its position is a chapter — and printing "page 4 of
 * 40" over an EPUB chapter index states something untrue about the file. Paged documents get a page
 * counter; reflowable ones get the chapter's own name, falling back to its number for a document
 * that does not name its chapters.
 */
@Composable
internal fun ReaderUiState.positionDescription(): String = when {
    isPageImages -> stringResource(
        com.mylibrary.core.ui.R.string.ui_page_of,
        (currentUnit + 1).coerceAtMost(totalUnits),
        totalUnits,
    )

    positionLabel != null -> positionLabel

    else -> stringResource(R.string.reader_chapter_of, currentUnit + 1, totalUnits)
}

/**
 * What the progress bar says.
 *
 * Almost always the same as [positionDescription] — but a reflowable chapter that has been split
 * into pages has a position *inside* it, and that is the number worth showing next to a progress
 * bar, because it is the one that changes as the reader turns a page. The toolbar keeps the
 * chapter's name, where there is room for it.
 *
 * A page number is shown against whichever total the reader is counting — the whole book's once the
 * book has been measured, which is the default, or the chapter's otherwise. See [ProgressScope]. Both
 * are true and the reader can ask for either; what would be untrue is a page counted within a chapter
 * beside a bar drawn against the book.
 */
@Composable
internal fun ReaderUiState.progressDescription(): String {
    val bookPage = bookPageNumber
    val bookTotal = bookPageCount
    return when {
        bookPage != null && bookTotal != null ->
            stringResource(R.string.reader_page_of, bookPage, bookTotal)

        !isPageImages && reflowPageCount > 0 -> stringResource(
            R.string.reader_page_of,
            (reflowPage + 1).coerceAtMost(reflowPageCount),
            reflowPageCount,
        )

        else -> positionDescription()
    }
}

@Composable
private fun readerMenuActionLabel(action: ReaderMenuAction): String = stringResource(
    when (action) {
        ReaderMenuAction.TableOfContents -> R.string.reader_toc
        ReaderMenuAction.Search -> R.string.reader_search_action
        ReaderMenuAction.Bookmarks -> R.string.reader_bookmarks
    },
)

private fun readerMenuActionIcon(action: ReaderMenuAction): ImageVector = when (action) {
    ReaderMenuAction.TableOfContents -> Icons.AutoMirrored.Filled.MenuBook
    ReaderMenuAction.Search -> Icons.Filled.Search
    ReaderMenuAction.Bookmarks -> Icons.Filled.BookmarkBorder
}
