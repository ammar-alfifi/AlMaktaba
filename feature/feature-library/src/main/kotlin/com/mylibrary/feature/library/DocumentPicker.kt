package com.mylibrary.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.usecase.ImportCandidate

/**
 * The Storage Access Framework picker, wrapped as a callback.
 *
 * MyLibrary never requests a storage permission. The user picks the files — or a whole folder — and
 * the system hands back a grant scoped to exactly those documents. This is also why importing is
 * instant: only the URI is recorded, so adding a 500 MB comic archive copies nothing.
 *
 * @return a function that launches the picker.
 */
@Composable
fun rememberDocumentPicker(onPicked: (List<ImportCandidate>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        currentOnPicked(uris.mapNotNull { uri -> context.toImportCandidate(uri) })
    }

    return remember(launcher) {
        {
            // The MIME filter is wide on purpose — see `BookFormat.pickerMimeTypes`. A narrow
            // filter greys out comic archives that providers mislabel as `application/zip`, which
            // looks like the app cannot open the user's own files.
            launcher.launch(BookFormat.pickerMimeTypes.toTypedArray())
        }
    }
}

/**
 * The folder picker: `OpenDocumentTree` wrapped as a callback.
 *
 * Handed on as a plain string, and the grant is deliberately *not* taken here. A folder is a tree
 * rather than a file: its permission has to be persisted, checked before every later use and released
 * when the user removes the folder, and all three of those belong with the code that reads the tree —
 * `FolderScanner` in `:core:core-data`. Taking it here as well would mean two places that decide what
 * "the app may read this folder" means.
 *
 * @return a function that launches the picker.
 */
@Composable
fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) currentOnPicked(uri.toString())
    }

    return remember(launcher) {
        {
            // No initial URI: the picker opens wherever the user was last time, which is where the
            // folder they are about to choose usually is.
            launcher.launch(null)
        }
    }
}

/**
 * Turns a picked [uri] into an [ImportCandidate].
 *
 * The persistable permission is taken *before* anything else, because without it the grant lasts
 * only until the process dies and every book imported this session would fail to open the next
 * time the app starts. Failure to take it is ignored rather than fatal: some providers offer
 * only a transient grant, and the book still works for as long as it lasts.
 *
 * Returns `null` only when the provider cannot even name the file, which means there is nothing
 * usable to record. Whether the URI is one the reader can actually open is not decided here: a
 * `file://` path a malformed intent carries still gets a candidate, and `ImportBooksUseCase`
 * refuses it as unsupported, which lets the UI report the failure instead of swallowing it.
 *
 * Shared rather than private because the same conversion is needed on the other road in: a book
 * the rest of the system hands over (a VIEW or SEND intent) is converted by `MainActivity` with
 * exactly this logic, so a file opened from a file manager lands in the library the same way a
 * file picked from the shelf does.
 */
fun Context.toImportCandidate(uri: Uri): ImportCandidate? {
    runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    var displayName: String? = null
    var sizeBytes = 0L
    runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)

                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }

    val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
    val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()

    return ImportCandidate(
        uri = uri.toString(),
        displayName = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )
}
