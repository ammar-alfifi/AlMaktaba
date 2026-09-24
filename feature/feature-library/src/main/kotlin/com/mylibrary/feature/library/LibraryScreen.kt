package com.mylibrary.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.usecase.FolderImportSummary
import com.mylibrary.core.ui.component.BookCover
import com.mylibrary.core.ui.component.BookGridCard
import com.mylibrary.core.ui.component.BookListRow
import com.mylibrary.core.ui.component.EmptyState
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.format.progressLabel
import com.mylibrary.core.ui.mvi.ObserveEffects
import com.mylibrary.core.ui.theme.Spacing

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
    val pickFolder = rememberFolderPicker { treeUri ->
        viewModel.onIntent(LibraryIntent.ImportFolderPicked(treeUri))
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
        onPickFiles = pickDocuments,
        onPickFolder = pickFolder,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onIntent: (LibraryIntent) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var pendingDeletion by remember { mutableStateOf<Book?>(null) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var deletingFolder by remember { mutableStateOf<Folder?>(null) }
    var managingFolders by remember { mutableStateOf(false) }
    var confirmingBatchDelete by remember { mutableStateOf(false) }

    FeatureScaffold(
        modifier = modifier,
        topBar = {
            if (state.isSelecting) {
                LibrarySelectionTopBar(
                    selectedCount = state.selectedBookIds.size,
                    onRequestDelete = { confirmingBatchDelete = true },
                    onIntent = onIntent,
                )
            } else {
                LibraryTopBar(
                    sort = state.sort,
                    viewMode = state.viewMode,
                    onIntent = onIntent,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Hidden while selecting: the one action left on screen is the selection's, and an add
            // button over a shelf being pruned is a button in the way.
            if (!state.isSelecting) {
                ImportFab(onPickFiles = onPickFiles, onPickFolder = onPickFolder)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.folders.isNotEmpty()) {
                FolderChipRow(
                    state = state,
                    onIntent = onIntent,
                    onManage = { managingFolders = true },
                )
            }

            if (state.availableFormats.isNotEmpty() || state.totalBookCount > 0) {
                LibraryFilterRow(state = state, onIntent = onIntent)
            }

            if (state.isImporting) {
                Column {
                    state.importingFolderName?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(
                            text = stringResource(R.string.lib_folder_importing, name),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = Spacing.Large,
                                end = Spacing.Large,
                                bottom = Spacing.ExtraSmall,
                            ),
                        )
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // The one thing most readers open the app to do, above the shelf rather than inside it:
            // it stays put while the grid scrolls, and it follows the folder chip — see
            // [ContinueReadingCard].
            state.continueReading?.let { item ->
                ContinueReadingCard(
                    item = item,
                    folderName = state.activeFolder?.name,
                    onClick = { onIntent(LibraryIntent.BookOpened(item.book.id)) },
                )
            }

            when {
                state.isEmptyLibrary -> EmptyState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.lib_empty_title),
                    message = stringResource(R.string.lib_empty_message),
                    action = {
                        TextButton(onClick = onPickFiles) {
                            Text(stringResource(R.string.lib_import_files))
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
                    // The same clearance the list uses: the floating button sits over the last row
                    // of a grid, and a grid padded by 16dp has its bottom-right cell underneath it.
                    contentPadding = PaddingValues(
                        start = Spacing.Large,
                        end = Spacing.Large,
                        top = Spacing.Large,
                        bottom = Spacing.FabClearance,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = state.items, key = { it.book.id }) { item ->
                        BookGridCard(
                            item = item,
                            onClick = {
                                if (state.isSelecting) {
                                    onIntent(LibraryIntent.ToggleSelection(item.book.id))
                                } else {
                                    onIntent(LibraryIntent.BookOpened(item.book.id))
                                }
                            },
                            onLongClick = {
                                if (state.isSelecting) {
                                    onIntent(LibraryIntent.ToggleSelection(item.book.id))
                                } else {
                                    onIntent(LibraryIntent.BookLongPressed(item.book))
                                }
                            },
                            selected = item.book.id in state.selectedBookIds,
                        )
                    }
                }

                else -> LazyColumn(
                    // A list has no horizontal padding of its own — its rows carry their own — so the
                    // clearance here is only about the button.
                    contentPadding = PaddingValues(bottom = Spacing.FabClearance),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = state.items, key = { it.book.id }) { item ->
                        BookListRow(
                            item = item,
                            onClick = {
                                if (state.isSelecting) {
                                    onIntent(LibraryIntent.ToggleSelection(item.book.id))
                                } else {
                                    onIntent(LibraryIntent.BookOpened(item.book.id))
                                }
                            },
                            onLongClick = {
                                if (state.isSelecting) {
                                    onIntent(LibraryIntent.ToggleSelection(item.book.id))
                                } else {
                                    onIntent(LibraryIntent.BookLongPressed(item.book))
                                }
                            },
                            selected = item.book.id in state.selectedBookIds,
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

    if (managingFolders) {
        FolderManagerSheet(
            folders = state.folders,
            unavailableFolderIds = state.unavailableFolderIds,
            onIntent = onIntent,
            onRequestRename = { folder -> renamingFolder = folder },
            onRequestDelete = { folder -> deletingFolder = folder },
        )
        // The sheet closes itself by way of the intents it emits; a folder list that empties — by
        // deleting the last folder — takes the sheet with it.
        if (state.folders.isEmpty()) managingFolders = false
    }

    renamingFolder?.let { folder ->
        FolderRenameDialog(
            folder = folder,
            onConfirm = { name ->
                onIntent(LibraryIntent.RenameFolder(folder.id, name))
                renamingFolder = null
            },
            onDismiss = { renamingFolder = null },
        )
    }

    deletingFolder?.let { folder ->
        FolderDeleteDialog(
            folder = folder,
            bookCount = state.folders.firstOrNull { it.folder.id == folder.id }?.bookCount ?: 0,
            onConfirm = { deleteContents ->
                onIntent(LibraryIntent.DeleteFolder(folder.id, deleteContents))
                deletingFolder = null
            },
            onDismiss = { deletingFolder = null },
        )
    }

    state.moveTarget?.let { book ->
        FolderMoveSheet(
            book = book,
            folders = state.folders,
            unavailableFolderIds = state.unavailableFolderIds,
            onIntent = onIntent,
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

    if (confirmingBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmingBatchDelete = false },
            title = { Text(stringResource(R.string.lib_delete_selected_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.lib_delete_selected_message,
                        state.selectedBookIds.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIntent(LibraryIntent.DeleteSelected)
                        confirmingBatchDelete = false
                    },
                ) {
                    Text(stringResource(R.string.lib_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingBatchDelete = false }) {
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
                    contentDescription = stringResource(R.string.lib_view_mode_cd),
                )
            }
        },
    )
}

/**
 * The toolbar while books are being selected.
 *
 * It replaces the shelf's own toolbar rather than stacking under it: in selection mode there is one
 * job, and the sort and view controls are not part of it. The count in the title is the feedback that
 * says the taps are landing, and the three actions are the ones a shelf asks for in bulk — select
 * all, favourite and delete. Moving several books stays on the single-book menu, where the
 * destination picker already lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySelectionTopBar(
    selectedCount: Int,
    onRequestDelete: () -> Unit,
    onIntent: (LibraryIntent) -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.lib_selected_count, selectedCount)) },
        navigationIcon = {
            IconButton(onClick = { onIntent(LibraryIntent.ClearSelection) }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(com.mylibrary.core.ui.R.string.ui_close),
                )
            }
        },
        actions = {
            IconButton(onClick = { onIntent(LibraryIntent.SelectAll) }) {
                Icon(
                    imageVector = Icons.Filled.SelectAll,
                    contentDescription = stringResource(R.string.lib_select_all),
                )
            }
            IconButton(
                onClick = { onIntent(LibraryIntent.FavoriteSelected) },
                enabled = selectedCount > 0,
            ) {
                Icon(
                    imageVector = Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(R.string.lib_menu_add_favorite),
                )
            }
            IconButton(onClick = onRequestDelete, enabled = selectedCount > 0) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(com.mylibrary.core.ui.R.string.ui_delete),
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
 * The "continue reading" button.
 *
 * It answers the question the reader arrived with — *where was I?* — and it is scoped to the folder
 * chip that is selected, because a device folder is a series: with one open, the book to carry on
 * with is that series' own, which is rarely the most recent book in the whole library.
 *
 * A row rather than a button because it has to say *which* book it would open: an unlabelled
 * "continue" leaves the reader to guess, and the guess is exactly what they came to avoid. The whole
 * row is the touch target — `clickable` merges its children into one node for a screen reader, so
 * the cover, the title and the progress are announced together with the action.
 */
@Composable
private fun ContinueReadingCard(
    item: LibraryItem,
    folderName: String?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.Large,
                end = Spacing.Large,
                top = Spacing.Small,
                bottom = Spacing.ExtraSmall,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            BookCover(
                book = item.book,
                modifier = Modifier.width(36.dp),
                contentDescription = null,
                // No format chip: at this size it is wider than the cover and covers it, and the row
                // already says which book it is in words — the chip would hide the artwork the reader
                // recognises the book by.
                showFormatBadge = false,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folderName?.let {
                        stringResource(R.string.lib_continue_in_folder, it)
                    } ?: stringResource(com.mylibrary.core.ui.R.string.ui_continue_reading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The same caption the shelf's own cards carry, so the percentage here and the
                    // one on the card below it can never disagree.
                    text = progressLabel(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
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
                onClick = { onIntent(LibraryIntent.MoveBookRequested(book)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.lib_folder_move), modifier = Modifier.fillMaxWidth())
            }

            TextButton(
                onClick = { onIntent(LibraryIntent.StartSelection(book.id)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.lib_menu_select), modifier = Modifier.fillMaxWidth())
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


/**
 * The library's single action.
 *
 * A menu rather than a button, because there are two ways to add books and they lead somewhere
 * different: files are added one by one and stand alone, while a folder brings a whole series in and
 * keeps it together. An extended button labelled "Add books" that then asked which of the two the
 * user meant would be a dialog in the way of the common case; a menu shows both, and the file path —
 * which is what almost every add is — is still two taps.
 */
@Composable
private fun ImportFab(onPickFiles: () -> Unit, onPickFolder: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        ExtendedFloatingActionButton(
            onClick = { menuOpen = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.lib_import_add)) },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_import_files)) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                },
                onClick = {
                    menuOpen = false
                    onPickFiles()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_import_folder)) },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onPickFolder()
                },
            )
        }
    }
}

/**
 * The folder chips: the whole library, then one chip per device folder.
 *
 * Only shown once a folder exists — a lone "All books" chip on a phone is a control that does
 * nothing and takes a row from the shelf. Tapping the selected chip clears the filter, so the row
 * does not need a separate way back to everything.
 *
 * A folder whose permission has lapsed is shown struck through rather than hidden: the reader's
 * series is still in their library, and a chip that vanished would look like the app losing it.
 */
@Composable
private fun FolderChipRow(
    state: LibraryUiState,
    onIntent: (LibraryIntent) -> Unit,
    onManage: () -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.Large, vertical = Spacing.ExtraSmall),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            FilterChip(
                selected = state.folderFilter == null,
                onClick = { onIntent(LibraryIntent.FolderSelected(null)) },
                label = { Text(stringResource(R.string.lib_folder_all)) },
            )
        }

        items(items = state.folders, key = { it.folder.id }) { summary ->
            val unavailable = summary.folder.id in state.unavailableFolderIds
            FilterChip(
                selected = state.folderFilter == summary.folder.id,
                // Selecting the selected chip clears the filter, so the row needs no separate way
                // back to everything.
                onClick = { onIntent(LibraryIntent.FolderSelected(summary.folder.id)) },
                label = {
                    Text(
                        text = summary.folder.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = if (unavailable) {
                            MaterialTheme.typography.labelLarge.copy(
                                textDecoration = TextDecoration.LineThrough,
                            )
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (unavailable) {
                            Icons.Filled.FolderOff
                        } else {
                            Icons.Filled.Folder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                // The count is what makes a chip recognisable as a series rather than a name to open.
                trailingIcon = {
                    Text(
                        text = summary.bookCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }

        // Rescan, rename and delete live behind this rather than behind a long press on a chip: a
        // gesture with nothing on screen announcing it is a feature most readers never find, and a
        // long press on a chip competes with the chip's own tap.
        item {
            AssistChip(
                onClick = onManage,
                label = { Text(stringResource(R.string.lib_folders_manage)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
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
    LibraryMessage.BooksFavorited -> stringResource(R.string.lib_books_favorited)

    is LibraryMessage.FolderImported -> folderSummaryText(message.summary, rescan = false)
    is LibraryMessage.FolderRescanned -> folderSummaryText(message.summary, rescan = true)
    LibraryMessage.FolderPermissionLost -> stringResource(R.string.lib_folder_permission_lost)
    LibraryMessage.FolderRenamed -> stringResource(R.string.lib_folder_renamed)
    is LibraryMessage.FolderDeleted -> stringResource(R.string.lib_folder_deleted)
    is LibraryMessage.FolderScanTruncated ->
        stringResource(R.string.lib_folder_scan_truncated, message.limit)

    is LibraryMessage.BookMoved -> message.folderName?.let { name ->
        stringResource(R.string.lib_book_moved, name)
    } ?: stringResource(R.string.lib_book_unfiled)
}

/**
 * What a folder scan says it did.
 *
 * Four outcomes, and each says something different about the reader's library: nothing supported in
 * the folder, everything already there, a rescan that found something new, and a rescan that found
 * some files gone from the device. Collapsing them into "done" would leave a reader who pointed at
 * the wrong folder with no way to tell.
 */
@Composable
private fun folderSummaryText(summary: FolderImportSummary, rescan: Boolean): String = when {
    summary.imported == 0 && summary.total == 0 && summary.failedDirectories > 0 ->
        stringResource(R.string.lib_folder_imported_nothing)

    summary.imported == 0 && summary.unsupported == summary.total && summary.total > 0 ->
        stringResource(R.string.lib_folder_imported_nothing)

    summary.imported == 0 && rescan && summary.missing > 0 ->
        stringResource(R.string.lib_folder_rescanned_missing, 0, summary.missing)

    summary.imported == 0 && rescan -> stringResource(R.string.lib_folder_rescan_all_present, summary.folderName)
    summary.imported == 0 -> stringResource(R.string.lib_folder_imported_all_present, summary.folderName)

    summary.missing > 0 ->
        stringResource(R.string.lib_folder_rescanned_missing, summary.imported, summary.missing)

    summary.alreadyInLibrary == 0 && summary.unsupported == 0 ->
        stringResource(R.string.lib_folder_imported, summary.imported, summary.folderName)

    else -> stringResource(
        R.string.lib_folder_imported_with_skipped,
        summary.imported,
        summary.folderName,
        summary.alreadyInLibrary,
        summary.unsupported,
    )
}
