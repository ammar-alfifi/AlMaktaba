package com.mylibrary.core.domain.usecase

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.domain.engine.FolderScanner
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import com.mylibrary.core.domain.repository.FolderRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What importing or re-scanning a folder actually did, so the screen can report it honestly. */
data class FolderImportSummary(
    val folderName: String,
    val imported: Int,
    val alreadyInLibrary: Int,
    val unsupported: Int,
    /** Books filed under this folder whose files are no longer in it. Reported, never deleted. */
    val missing: Int,
    /** True when the app could not read the folder at all: the grant is gone. */
    val permissionDenied: Boolean,
    /** True when the walk stopped at the scanner's ceiling. */
    val truncated: Boolean,
    val failedDirectories: Int,
) {
    val total: Int get() = imported + alreadyInLibrary + unsupported

    companion object {
        /** The result of a scan that never happened, for a folder the app cannot open. */
        fun denied(folderName: String) = FolderImportSummary(
            folderName = folderName,
            imported = 0,
            alreadyInLibrary = 0,
            unsupported = 0,
            missing = 0,
            permissionDenied = true,
            truncated = false,
            failedDirectories = 0,
        )
    }
}

/** The folders the user has added, with how many books each holds. */
class ObserveFoldersUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
) {
    operator fun invoke(): Flow<List<FolderSummary>> = folderRepository.observeFolders()
}

/**
 * Adds a device folder to the library and imports what is in it.
 *
 * The order of the steps is the design. The folder row is written **before** a single file is
 * enumerated, so that a user who leaves the screen mid-scan — or a provider that dies halfway — is
 * left with the folder remembered and whatever was imported so far, rather than with nothing to show
 * for a minute of waiting. Books are inserted as they are read, for the same reason.
 */
class ImportFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
    private val importBooks: ImportBooksUseCase,
    private val scanner: FolderScanner,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(treeUri: String): FolderImportSummary = withContext(dispatchers.io) {
        val fallbackName = scanner.displayName(treeUri) ?: treeUri.substringAfterLast('/')

        // A folder the app cannot read must not become a chip in the library: it would filter to
        // nothing and look like a bug in the app rather than a permission that needs renewing.
        if (!scanner.takePermission(treeUri)) {
            return@withContext FolderImportSummary.denied(fallbackName)
        }

        val existing = folderRepository.findFolderByUri(treeUri)
        val name = existing?.name ?: fallbackName
        val folderId = folderRepository.saveFolder(
            Folder(
                id = existing?.id ?: Folder.NO_ID,
                uri = treeUri,
                name = name,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            ),
        )

        val scan = scanner.scan(treeUri)
        val summary = importBooks(
            scan.entries.map { entry ->
                ImportCandidate(
                    uri = entry.uri,
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    sizeBytes = entry.sizeBytes,
                    folderId = folderId,
                )
            },
        )
        folderRepository.markScanned(folderId)

        FolderImportSummary(
            folderName = name,
            imported = summary.imported,
            alreadyInLibrary = summary.alreadyInLibrary,
            unsupported = summary.unsupported,
            missing = 0,
            permissionDenied = false,
            truncated = scan.truncated,
            failedDirectories = scan.failedDirectories,
        )
    }
}

/**
 * Re-reads a folder, importing whatever has been added to it since.
 *
 * Files that have *gone* are counted and reported, never deleted: a book whose file is on a card that
 * is not mounted, or in a directory that was renamed, is indistinguishable from one that was
 * genuinely removed — and deleting it would take the reader's bookmarks and reading position with
 * it. The reader is told the count and can decide.
 */
class RescanFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
    private val libraryRepository: LibraryRepository,
    private val importBooks: ImportBooksUseCase,
    private val scanner: FolderScanner,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(folderId: Long): FolderImportSummary = withContext(dispatchers.io) {
        val folder = folderRepository.getFolder(folderId)
            ?: return@withContext FolderImportSummary.denied("")

        if (!scanner.hasPermission(folder.uri)) {
            return@withContext FolderImportSummary.denied(folder.name)
        }

        val scan = scanner.scan(folder.uri)
        val foundUris = scan.entries.map { it.uri }.toSet()

        // Only the files that are not already filed under this folder are handed to the importer, so
        // re-scanning a hundred-book series does not re-read a hundred metadata blocks to discover
        // that nothing has changed.
        val knownUris = folderRepository.bookIdsInFolder(folderId)
            .mapNotNull { bookId -> libraryRepository.getBook(bookId)?.uri }
            .toSet()

        val summary = importBooks(
            scan.entries
                .filter { it.uri !in knownUris }
                .map { entry ->
                    ImportCandidate(
                        uri = entry.uri,
                        displayName = entry.displayName,
                        mimeType = entry.mimeType,
                        sizeBytes = entry.sizeBytes,
                        folderId = folderId,
                    )
                },
        )
        folderRepository.markScanned(folderId)

        FolderImportSummary(
            folderName = folder.name,
            imported = summary.imported,
            alreadyInLibrary = summary.alreadyInLibrary,
            unsupported = summary.unsupported,
            missing = knownUris.count { uri -> uri !in foundUris },
            permissionDenied = false,
            truncated = scan.truncated,
            failedDirectories = scan.failedDirectories,
        )
    }
}

class RenameFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
) {
    suspend operator fun invoke(folderId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        folderRepository.renameFolder(folderId, trimmed)
    }
}

/**
 * Removes a folder from the library.
 *
 * [deleteContents] decides everything: with it, the books the folder contributed are removed from the
 * library — which takes their bookmarks and reading positions with them, exactly as deleting a book
 * anywhere else does. Without it, the books stay and simply stop being filed. The grant is released
 * either way: a persisted permission for a folder the app no longer shows is one Android counts
 * against it.
 */
class DeleteFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
    private val deleteBooks: DeleteBooksUseCase,
    private val scanner: FolderScanner,
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(folderId: Long, deleteContents: Boolean) =
        withContext(dispatchers.io) {
            val folder = folderRepository.getFolder(folderId) ?: return@withContext

            if (deleteContents) {
                val bookIds = folderRepository.bookIdsInFolder(folderId)
                if (bookIds.isNotEmpty()) deleteBooks(bookIds)
            }
            folderRepository.deleteFolder(folderId)
            scanner.releasePermission(folder.uri)
        }
}

/** Files a book under a folder, or clears its folder when [folderId] is `null`. */
class MoveBookToFolderUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
) {
    suspend operator fun invoke(bookId: Long, folderId: Long?) =
        folderRepository.assignBooks(listOf(bookId), folderId)
}
