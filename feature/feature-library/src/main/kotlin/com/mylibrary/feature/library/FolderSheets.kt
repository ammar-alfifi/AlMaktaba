package com.mylibrary.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import com.mylibrary.core.ui.theme.Spacing

/**
 * The folder dialogs and sheets.
 *
 * Kept out of `LibraryScreen` because that file is already a screen's worth of layout, and because
 * these are four self-contained conversations with the user — rescan, rename, move, delete — none of
 * which needs to know anything else on the page.
 */

/**
 * Managing the folders the library is grouped by.
 *
 * A list rather than a long-press on a chip, for two reasons that both come down to the same thing.
 * A long press on a `FilterChip` fights the chip's own click handling — the gesture that opens and
 * the gesture that selects share a surface — and, worse, nothing on screen would say the action
 * exists. Here every folder is listed with what is in it, and each row carries its own menu.
 *
 * Rescan is the action a reader of a running series actually wants, so it is the first item in that
 * menu: new volumes land in the folder and the shelf is two taps from picking them up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderManagerSheet(
    folders: List<FolderSummary>,
    unavailableFolderIds: Set<Long>,
    onIntent: (LibraryIntent) -> Unit,
    onRequestRename: (Folder) -> Unit,
    onRequestDelete: (Folder) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { onIntent(LibraryIntent.DismissFolderMenu) },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.XLarge)) {
            Text(
                text = stringResource(R.string.lib_folders_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.XLarge, vertical = Spacing.Small),
            )

            folders.forEach { summary ->
                val folder = summary.folder
                val isAvailable = folder.id !in unavailableFolderIds
                ListItem(
                    headlineContent = {
                        Text(text = folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = if (isAvailable) {
                                stringResource(R.string.lib_folder_book_count, summary.bookCount)
                            } else {
                                stringResource(R.string.lib_folder_unavailable_message)
                            },
                            maxLines = 2,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (isAvailable) Icons.Filled.Folder else Icons.Filled.FolderOff,
                            contentDescription = null,
                            tint = if (isAvailable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                    trailingContent = {
                        FolderRowMenu(
                            folder = folder,
                            isAvailable = isAvailable,
                            onIntent = onIntent,
                            onRequestRename = onRequestRename,
                            onRequestDelete = onRequestDelete,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Tapping the row filters the library to it and closes the sheet, which is
                        // what a reader who opened this to find a series expects.
                        .clickable {
                            onIntent(LibraryIntent.FolderSelected(folder.id))
                            onIntent(LibraryIntent.DismissFolderMenu)
                        },
                )
            }
        }
    }
}

/** One folder's actions: rescan, rename, delete. */
@Composable
private fun FolderRowMenu(
    folder: Folder,
    isAvailable: Boolean,
    onIntent: (LibraryIntent) -> Unit,
    onRequestRename: (Folder) -> Unit,
    onRequestDelete: (Folder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.lib_folder_cd),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_folder_menu_rescan)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                enabled = isAvailable,
                onClick = {
                    expanded = false
                    onIntent(LibraryIntent.RescanFolder(folder.id))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_folder_menu_rename)) },
                leadingIcon = {
                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onRequestRename(folder)
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.lib_folder_menu_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onRequestDelete(folder)
                },
            )
        }
    }
}

/** Renaming a folder. The confirm button stays disabled while the field is empty. */
@Composable
fun FolderRenameDialog(
    folder: Folder,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(folder.id) { mutableStateOf(folder.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_folder_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.lib_folder_rename_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(com.mylibrary.core.ui.R.string.ui_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.mylibrary.core.ui.R.string.ui_cancel))
            }
        },
    )
}

/**
 * Removing a folder.
 *
 * Two choices in one dialog, and the destructive one is a checkbox that starts *off*: removing the
 * folder alone keeps every book and is what someone tidying their shelves wants, while deleting the
 * books is what someone who is done with a series wants. The prose says which is which before the
 * user picks, rather than after.
 */
@Composable
fun FolderDeleteDialog(
    folder: Folder,
    bookCount: Int,
    onConfirm: (deleteContents: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteContents by remember(folder.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_folder_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                Text(stringResource(R.string.lib_folder_delete_message, folder.name, bookCount))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable { deleteContents = !deleteContents }
                        .padding(vertical = Spacing.Small),
                ) {
                    Checkbox(checked = deleteContents, onCheckedChange = { deleteContents = it })
                    Text(
                        text = stringResource(R.string.lib_folder_delete_contents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteContents) }) {
                Text(
                    text = stringResource(R.string.lib_folder_menu_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.mylibrary.core.ui.R.string.ui_cancel))
            }
        },
    )
}

/**
 * Moving a book between folders.
 *
 * Every folder is listed, with "no folder" first: the current arrangement is shown by a tick rather
 * than by hiding the book's own folder, so a reader who opens this by accident can see where the
 * book is. Nothing here creates a folder — folders come from the device, and inventing one from this
 * sheet would be a second, confusing way to make one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMoveSheet(
    book: Book,
    folders: List<FolderSummary>,
    unavailableFolderIds: Set<Long>,
    onIntent: (LibraryIntent) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { onIntent(LibraryIntent.DismissMoveSheet) },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.XLarge)) {
            Text(
                text = stringResource(R.string.lib_folder_move_title, book.title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.XLarge, vertical = Spacing.Small),
            )

            MoveTargetRow(
                name = stringResource(R.string.lib_folder_move_none),
                selected = book.folderId == null,
                onClick = { onIntent(LibraryIntent.MoveBookToFolder(book.id, null)) },
            )

            folders.forEach { summary ->
                MoveTargetRow(
                    name = summary.folder.name,
                    selected = book.folderId == summary.folder.id,
                    // A folder the app can no longer read is still a valid place to file a book —
                    // the association is in this app's database — so it is offered, and marked.
                    subtitle = if (summary.folder.id in unavailableFolderIds) {
                        stringResource(R.string.lib_folder_unavailable)
                    } else {
                        stringResource(R.string.lib_folder_book_count, summary.bookCount)
                    },
                    onClick = { onIntent(LibraryIntent.MoveBookToFolder(book.id, summary.folder.id)) },
                )
            }
        }
    }
}

@Composable
private fun MoveTargetRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    ListItem(
        headlineContent = { Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { text -> { Text(text = text) } },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick),
    )
}
