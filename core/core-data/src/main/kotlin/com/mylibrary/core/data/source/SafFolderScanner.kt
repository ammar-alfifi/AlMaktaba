package com.mylibrary.core.data.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.common.naturalSortKey
import com.mylibrary.core.domain.engine.FolderEntry
import com.mylibrary.core.domain.engine.FolderScan
import com.mylibrary.core.domain.engine.FolderScanner
import com.mylibrary.core.domain.model.BookFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [FolderScanner] over the Storage Access Framework.
 *
 * The only file in MyLibrary that knows a folder is a `DocumentFile` over a tree `Uri`, in the same
 * way [ContentDocumentSource] is the only one that knows a document is a `content://` URI. Everything
 * above it — the import rules, the library, the chips — sees [FolderEntry] values and a folder name.
 *
 * **The permission is read-only, and taken here rather than in the UI.** Asking for write access on
 * an `OpenDocumentTree` result throws on a number of providers, and there is nothing to write: books
 * are read where they are and never copied. Taking the grant inside the scanner also means the
 * folder's whole lifecycle — taken, checked, released — sits next to the code that walks it, instead
 * of being split across two layers that have to agree.
 */
@Singleton
class SafFolderScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : FolderScanner {

    override fun takePermission(treeUri: String): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            Uri.parse(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }.isSuccess

    override fun releasePermission(treeUri: String) {
        // Ignored rather than reported: the user asked for the folder to go, and a grant that had
        // already lapsed is not a failure of that.
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    override fun hasPermission(treeUri: String): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri.toString() == treeUri
        }
    }.getOrDefault(false)

    override fun displayName(treeUri: String): String? = runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name
    }.getOrNull()

    override suspend fun scan(treeUri: String): FolderScan = withContext(dispatchers.io) {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
            ?: return@withContext FolderScan(entries = emptyList(), failedDirectories = 1)

        val entries = ArrayList<FolderEntry>()
        var failed = 0
        var truncated = false

        // An explicit stack rather than recursion: a deep tree should not be able to overflow the
        // stack of a coroutine that is also holding a transaction's worth of state.
        val pending = ArrayDeque<Pair<DocumentFile, Int>>()
        pending.addLast(root to 0)

        while (pending.isNotEmpty() && !truncated) {
            val (directory, depth) = pending.removeLast()

            // Cancellation is honoured between directories, not within one: leaving the screen must
            // stop a long walk promptly, and a directory listing is the smallest unit that can be
            // abandoned without losing work — everything found so far is already in the library.
            currentCoroutineContext().ensureActive()

            val children = runCatching { directory.listFiles() }.getOrElse {
                // One unreadable directory must not abandon the folder: a revoked grant on a
                // subdirectory, or a provider that fails on a single entry.
                failed++
                emptyArray()
            }

            for (child in children) {
                val name = child.name ?: continue
                if (isJunk(name)) continue

                if (child.isDirectory) {
                    if (depth + 1 < FolderScanner.MAX_DEPTH) pending.addLast(child to (depth + 1))
                    continue
                }

                val format = BookFormat.fromExtension(name.substringAfterLast('.', ""))
                    ?: BookFormat.fromMimeType(child.type)
                if (format == null) continue

                if (entries.size >= FolderScanner.MAX_ENTRIES) {
                    truncated = true
                    break
                }
                entries += child.toEntry(name)
            }
        }

        // Sorted naturally: a series' volumes are named `Vol 2` and `Vol 10`, and a plain string sort
        // would import the tenth before the second. The library's own sort orders re-order them
        // afterwards; this is the order the import happens in, which is the order a reader watching
        // the progress bar expects to see.
        FolderScan(
            entries = entries.sortedWith(compareBy { naturalSortKey(it.relativePath) }),
            truncated = truncated,
            failedDirectories = failed,
        )
    }

    /** Name and size from the provider, falling back to what the URI itself says. */
    private fun DocumentFile.toEntry(name: String): FolderEntry {
        var size = length()
        var mime = type

        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }

        if (mime.isNullOrBlank()) mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()

        return FolderEntry(
            uri = uri.toString(),
            displayName = name,
            mimeType = mime,
            sizeBytes = size,
            relativePath = name,
        )
    }

    /**
     * Whether an entry is a file system's leavings rather than a book.
     *
     * These arrive from every direction — a Mac's `__MACOSX` resource forks, Windows' `Thumbs.db`, a
     * `.DS_Store` per directory — and none of them is a book the reader wants on a shelf.
     */
    private fun isJunk(name: String): Boolean =
        name.startsWith(".") || name == "__MACOSX" || name == "Thumbs.db"
}
