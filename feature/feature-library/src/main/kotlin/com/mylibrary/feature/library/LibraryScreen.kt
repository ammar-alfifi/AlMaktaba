package com.mylibrary.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.ui.component.BookGridCard
import com.mylibrary.core.ui.component.BookListRow
import com.mylibrary.core.ui.component.EmptyState
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.mvi.ObserveEffects

/**
 * The library destination.
 *
 * Navigation is *not* performed here: the ViewModel emits an effect and this route reports it
 * upward, so the screen stays usable from a `@Preview` and from a test without a navigation host.
 */
@Composable
fun LibraryRoute(
    onOpenBook: (Long) -> Unit,
    onOpenDetails: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickDocuments = rememberDocumentPicker { candidates ->
        viewModel.onIntent(LibraryIntent.ImportPicked(candidates))
    }

    // Effects carry a LibraryMessage rather than a formatted string, because formatting has to
    // happen inside composition where the current locale is available. Holding the message in
    // state lets the text be resolved composably and then shown once.
    var pendingMessage by remember { mutableStateOf<LibraryMessage?>(null) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is LibraryEffect.OpenBook -> onOpenBook(effect.bookId)
            is LibraryEffect.OpenDetails -> onOpenDetails(effect.bookId)
            is LibraryEffect.ShowMessage -> pendingMessage = effect.message
        }
    }

    val messageText = pendingMessage?.let { message -> libraryMessageText(message) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            pendingMessage = null
        }
    }

    LibraryScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onImportClick = pickDocuments,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onIntent: (LibraryIntent) -> Unit,
    onImportClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var pendingDeletion by remember { mutableStateOf<Book?>(null) }

    FeatureScaffold(
        modifier = modifier,
        topBar = {
            LibraryTopBar(
                sort = state.sort,
                viewMode = state.viewMode,
                onIntent = onIntent,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImportClick,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.lib_import)) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.availableFormats.isNotEmpty() || state.totalBookCount > 0) {
                LibraryFilterRow(state = state, onIntent = onIntent)
            }

            if (state.isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                state.isEmptyLibrary -> EmptyState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.lib_empty_title),
                    message = stringResource(R.string.lib_empty_message),
                    action = {
                        TextButton(onClick = onImportClick) {
                            Text(stringResource(R.string.lib_import))
                        }
                    },
                )

                state.isFilteredWithoutMatches -> EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = stringResource(R.string.lib_no_matches_title),
                    message = stringResource(R.string.lib_no_matches_message),
                    action = {
                        TextButton(
                            onClick = {
                                if (state.favoritesOnly) onIntent(LibraryIntent.ToggleFavoritesFilter)
                                state.formatFilter.forEach { format ->
                                    onIntent(LibraryIntent.ToggleFormatFilter(format))
                                }
                            },
                        ) {
                            Text(stringResource(R.string.lib_clear_filters))
                        }
                    },
                )

                state.viewMode == ViewMode.GRID -> LazyVerticalGrid(
                    // Adaptive rather than a fixed count: one declaration covers a phone, a
                    // foldable and a tablet without a size-class branch in this file.
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = state.items, key = { it.book.id }) { item ->
                        BookGridCard(
                            item = item,
                            onClick = { onIntent(LibraryIntent.BookOpened(item.book.id)) },
                            onLongClick = { onIntent(LibraryIntent.BookLongPressed(item.book)) },
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = state.items, key = { it.book.id }) { item ->
                        BookListRow(
                            item = item,
                            onClick = { onIntent(LibraryIntent.BookOpened(item.book.id)) },
                            onLongClick = { onIntent(LibraryIntent.BookLongPressed(item.book)) },
                        )
                    }
                }
            }
        }
    }

    val menuTarget = state.menuTarget
    if (menuTarget != null) {
        BookContextSheet(
            book = menuTarget,
            onIntent = onIntent,
            onRequestDelete = { pendingDeletion = menuTarget },
        )
    }

    pendingDeletion?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(stringResource(R.string.lib_delete_title)) },
            text = { Text(stringResource(R.string.lib_delete_message, book.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIntent(LibraryIntent.DeleteBooks(listOf(book.id)))
                        pendingDeletion = null
                    },
                ) {
                    Text(stringResource(R.string.lib_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(com.mylibrary.core.ui.R.string.ui_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    sort: LibrarySort,
    viewMode: ViewMode,
    onIntent: (LibraryIntent) -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.lib_title)) },
        actions = {
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.lib_cd_sort),
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(option)) },
                            onClick = {
                                onIntent(LibraryIntent.SortChanged(option))
                                sortMenuOpen = false
                            },
                            leadingIcon = {
                                if (option == sort) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                } else {
                                    // Keeps the labels aligned whether or not a tick is present.
                                    Box(modifier = Modifier.size(24.dp))
                                }
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { onIntent(LibraryIntent.ToggleViewMode) }) {
                Icon(
                    imageVector = if (viewMode == ViewMode.GRID) {
                        Icons.AutoMirrored.Filled.ViewList
                    } else {
                        Icons.Filled.GridView
                    },
                    contentDescription = stringResource(R.string.lib_cd_view_mode),
                )
            }
        },
    )
}

/** The favourites toggle plus one chip per format actually present in the library. */
@Composable
private fun LibraryFilterRow(
    state: LibraryUiState,
    onIntent: (LibraryIntent) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            FilterChip(
                selected = state.favoritesOnly,
                onClick = { onIntent(LibraryIntent.ToggleFavoritesFilter) },
                label = { Text(stringResource(R.string.lib_filter_favorites)) },
            )
        }
        items(
            items = state.availableFormats.sortedBy { it.ordinal },
            key = { it.name },
        ) { format ->
            FilterChip(
                selected = format in state.formatFilter,
                onClick = { onIntent(LibraryIntent.ToggleFormatFilter(format)) },
                label = { Text(format.displayName) },
            )
        }
    }
}

/**
 * The long-press menu.
 *
 * A bottom sheet rather than a dropdown because these actions are destructive and need a
 * comfortable target; the delete confirmation is a separate dialog so that a mis-tap on the sheet
 * still cannot lose a book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookContextSheet(
    book: Book,
    onIntent: (LibraryIntent) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { onIntent(LibraryIntent.DismissMenu) },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(book.title) },
                supportingContent = {
                    Text(
                        text = listOfNotNull(
                            book.author?.takeIf { it.isNotBlank() },
                            book.format.displayName,
                        ).joinToString(" · "),
                    )
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )

            val favoriteLabel = if (book.isFavorite) {
                stringResource(R.string.lib_menu_remove_favorite)
            } else {
                stringResource(R.string.lib_menu_add_favorite)
            }
            TextButton(
                onClick = { onIntent(LibraryIntent.ToggleFavorite(book)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Text(favoriteLabel, modifier = Modifier.fillMaxWidth())
            }

            TextButton(
                onClick = {
                    onIntent(LibraryIntent.DismissMenu)
                    onIntent(LibraryIntent.DetailsRequested(book.id))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.lib_menu_details), modifier = Modifier.fillMaxWidth())
            }

            TextButton(
                onClick = {
                    onIntent(LibraryIntent.DismissMenu)
                    onRequestDelete()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.lib_menu_delete),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: LibrarySort): String = stringResource(
    when (sort) {
        LibrarySort.RECENTLY_READ -> R.string.lib_sort_recently_read
        LibrarySort.RECENTLY_ADDED -> R.string.lib_sort_recently_added
        LibrarySort.TITLE_ASC -> R.string.lib_sort_title_asc
        LibrarySort.TITLE_DESC -> R.string.lib_sort_title_desc
        LibrarySort.AUTHOR -> R.string.lib_sort_author
    },
)

/** Resolves an import/delete outcome into text in the user's current language. */
@Composable
private fun libraryMessageText(message: LibraryMessage): String = when (message) {
    is LibraryMessage.ImportFinished -> {
        val summary = message.summary
        when {
            summary.imported == 0 && summary.total == 0 -> stringResource(R.string.lib_import_nothing)
            summary.alreadyInLibrary == 0 && summary.unsupported == 0 ->
                stringResource(R.string.lib_import_result, summary.imported)
            else -> stringResource(
                R.string.lib_import_result_with_skipped,
                summary.imported,
                summary.alreadyInLibrary,
                summary.unsupported,
            )
        }
    }

    LibraryMessage.ImportFailed -> stringResource(com.mylibrary.core.ui.R.string.ui_error_unexpected)
    LibraryMessage.BookDeleted -> stringResource(R.string.lib_book_deleted)
    LibraryMessage.BooksDeleted -> stringResource(R.string.lib_book_deleted)
}
