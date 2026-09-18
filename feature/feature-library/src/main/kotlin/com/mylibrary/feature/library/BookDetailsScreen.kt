package com.mylibrary.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.usecase.BookDetails
import com.mylibrary.core.ui.component.BookCover
import com.mylibrary.core.ui.component.EmptyState
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.component.LoadingState
import com.mylibrary.core.ui.component.SectionHeader
import com.mylibrary.core.ui.format.bookMetaLine
import com.mylibrary.core.ui.format.relativeTime
import com.mylibrary.core.ui.mvi.ObserveEffects
import com.mylibrary.core.ui.theme.Spacing

@Composable
fun BookDetailsRoute(
    onOpenReader: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: BookDetailsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(com.mylibrary.core.ui.R.string.ui_undo)
    val bookmarkDeletedText = stringResource(R.string.lib_bookmark_deleted)

    var pendingUndo by remember { mutableStateOf<Bookmark?>(null) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is BookDetailsEffect.OpenReader -> onOpenReader(effect.bookId)
            BookDetailsEffect.Deleted,
            BookDetailsEffect.NavigateBack,
            -> onBack()

            is BookDetailsEffect.BookmarkDeleted -> pendingUndo = effect.bookmark
        }
    }

    // Deletion is undoable for as long as the snackbar is up, which is what turns a destructive
    // tap into a reversible one — the row vanishes, the reader decides, and the list either stays
    // or comes back.
    LaunchedEffect(pendingUndo) {
        val bookmark = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = bookmarkDeletedText,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.onIntent(BookDetailsIntent.UndoDeleteBookmark(bookmark))
        }
        pendingUndo = null
    }

    BookDetailsScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsScreen(
    state: BookDetailsUiState,
    onIntent: (BookDetailsIntent) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    FeatureScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lib_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(com.mylibrary.core.ui.R.string.ui_close),
                        )
                    }
                },
                actions = {
                    val book = state.details?.book
                    if (book != null) {
                        IconButton(onClick = { onIntent(BookDetailsIntent.ToggleFavorite) }) {
                            Icon(
                                imageVector = if (book.isFavorite) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Filled.FavoriteBorder
                                },
                                contentDescription = stringResource(R.string.lib_details_cd_favorite),
                                tint = if (book.isFavorite) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val details = state.details
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

            details == null -> EmptyState(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = stringResource(R.string.lib_details_not_found_title),
                message = stringResource(R.string.lib_details_not_found_message),
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.Large,
                    end = Spacing.Large,
                    top = Spacing.Large,
                    bottom = Spacing.Huge,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Large),
            ) {
                item { BookHeader(details = details, onIntent = onIntent) }

                item {
                    HorizontalDivider()
                }

                item { SectionHeader(stringResource(R.string.lib_details_bookmarks)) }

                if (details.bookmarks.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.lib_details_no_bookmarks),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(items = details.bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            onDelete = { onIntent(BookDetailsIntent.DeleteBookmark(bookmark.id)) },
                        )
                    }
                }

            }
        }

        // The one thing anyone opens this screen to do, pinned to the bottom rather than left at the
        // end of the scroll: a primary action that has to be scrolled to is one most readers never
        // see, and it was previously sitting below the bookmark list in an outlined — that is,
        // secondary — style.
        if (details != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { onIntent(BookDetailsIntent.Read) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Spacing.Large,
                            end = Spacing.Large,
                            top = Spacing.Medium,
                            bottom = Spacing.Medium,
                        ),
                ) {
                    Text(
                        text = if (details.position != null) {
                            stringResource(R.string.lib_details_continue)
                        } else {
                            stringResource(R.string.lib_details_read)
                        },
                    )
                }
            }
        }
    }
}

/** Cover, title, author, metadata and reading progress. */
@Composable
private fun BookHeader(details: BookDetails, onIntent: (BookDetailsIntent) -> Unit) {
    val book = details.book
    val position = details.position
    val compact = LocalConfiguration.current.screenWidthDp < 360

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Fixed on a phone, but not so wide that it squeezes the title column on a small one:
            // 120dp of a 320dp screen leaves 152dp for the title, the author and the metadata line.
            BookCover(
                book = book,
                modifier = Modifier.width(if (compact) 96.dp else 128.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.author?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.lib_details_unknown_author),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Bounded like the title above it: a list of contributors can be several lines
                    // long, and the metadata and progress below it must stay on screen.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = bookMetaLine(book),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (position != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lib_details_progress),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val percent = (position.percent * 100).toInt()
                    Text(
                        text = stringResource(
                            com.mylibrary.core.ui.R.string.ui_percent_read,
                            percent,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    // Bound to a local: `excerpt` is declared in another module, so the compiler
                    // cannot smart-cast the property access inside the branch.
                    val excerpt = position.excerpt
                    if (excerpt != null) {
                        Text(
                            text = excerpt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.lib_details_last_read,
                            relativeTime(position.updatedAt),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.lib_details_added_on, relativeTime(book.addedAt)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, onDelete: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                tint = bookmark.colorArgb
                    ?.let { androidx.compose.ui.graphics.Color(it) }
                    ?: MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(
                text = bookmark.label ?: bookmark.excerpt.orEmpty().take(60),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val supporting = buildString {
                bookmark.excerpt?.let { append(it.take(90)) }
                bookmark.note?.let {
                    if (isNotEmpty()) append(" — ")
                    append(it)
                }
                if (isEmpty()) append(locatorLabel(bookmark.locator))
            }
            Text(text = supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(com.mylibrary.core.ui.R.string.ui_delete),
                )
            }
        },
    )
}

/** A human-readable fallback for a bookmark that has neither a label nor an excerpt. */
@Composable
private fun locatorLabel(locator: ReadingLocator): String = when (locator) {
    is ReadingLocator.Paged -> stringResource(R.string.lib_page_number, locator.pageIndex + 1)
    is ReadingLocator.Reflowable -> stringResource(R.string.lib_chapter_number, locator.chapterIndex + 1)
}
