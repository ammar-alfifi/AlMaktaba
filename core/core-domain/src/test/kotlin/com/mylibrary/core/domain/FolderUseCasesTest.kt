package com.mylibrary.core.domain

import com.mylibrary.core.common.DefaultDispatcherProvider
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.usecase.DeleteBooksUseCase
import com.mylibrary.core.domain.usecase.DeleteFolderUseCase
import com.mylibrary.core.domain.usecase.ImportBooksUseCase
import com.mylibrary.core.domain.usecase.ImportFolderUseCase
import com.mylibrary.core.domain.usecase.ObserveLibraryUseCase
import com.mylibrary.core.domain.usecase.RenameFolderUseCase
import com.mylibrary.core.domain.usecase.RescanFolderUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Importing and maintaining a device folder.
 *
 * The rules here are the ones a reader would notice breaking: a series must arrive as one shelf, a
 * book already in the library must join that shelf rather than be duplicated, a re-scan must pick up
 * new volumes without re-reading the old ones, and — the one that matters most — no scan may ever
 * lose a book that is still in the library.
 */
class FolderUseCasesTest {

    private val library = FakeLibraryRepository()
    private val folders = FakeFolderRepository(library)
    private val scanner = FakeFolderScanner()
    private val documents = FakeDocumentRepository()
    private val progress = FakeProgressRepository()
    private val bookmarks = FakeBookmarkRepository()

    private val importBooks = ImportBooksUseCase(library, documents, DefaultDispatcherProvider())
    private val importFolder = ImportFolderUseCase(
        folderRepository = folders,
        importBooks = importBooks,
        scanner = scanner,
        dispatchers = DefaultDispatcherProvider(),
    )
    private val rescanFolder = RescanFolderUseCase(
        folderRepository = folders,
        libraryRepository = library,
        importBooks = importBooks,
        scanner = scanner,
        dispatchers = DefaultDispatcherProvider(),
    )
    private val renameFolder = RenameFolderUseCase(folders)
    private val deleteFolder = DeleteFolderUseCase(
        folderRepository = folders,
        deleteBooks = DeleteBooksUseCase(
            libraryRepository = library,
            progressRepository = progress,
            bookmarkRepository = bookmarks,
            dispatchers = DefaultDispatcherProvider(),
        ),
        scanner = scanner,
        dispatchers = DefaultDispatcherProvider(),
    )

    private val treeUri = "content://tree/Manga"

    // region Importing

    @Test
    fun `every supported file in a folder becomes a book filed under it`() = runTest {
        scanner.contents[treeUri] = mutableListOf(
            folderEntry("Vol 1/vol-1.epub"),
            folderEntry("Vol 2/vol-2.epub"),
            folderEntry("cover.jpg"),
        )

        val summary = importFolder(treeUri)

        assertEquals(2, summary.imported)
        assertEquals("a cover image is not a book", 1, summary.unsupported)
        val stored = library.books.value
        assertEquals(2, stored.size)
        assertEquals("every book carries the folder", 2, stored.count { it.folderId != null })
    }

    @Test
    fun `the folder is remembered even when it holds nothing the app can read`() = runTest {
        // A user who points at the wrong directory should be able to see which one they picked,
        // rather than have the app silently forget the whole attempt.
        scanner.contents[treeUri] = mutableListOf(folderEntry("notes.docx", extension = "docx"))

        val summary = importFolder(treeUri)

        assertEquals(0, summary.imported)
        assertEquals(1, folders.folders.value.size)
        assertEquals("Manga", folders.folders.value.first().name)
    }

    @Test
    fun `a folder whose grant is refused is not added at all`() = runTest {
        scanner.permissionGranted = false
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))

        val summary = importFolder(treeUri)

        assertTrue("the screen is told", summary.permissionDenied)
        assertTrue("and no chip appears for a folder that cannot be read", folders.folders.value.isEmpty())
        assertTrue(library.books.value.isEmpty())
    }

    @Test
    fun `importing the same folder twice keeps one folder and adds no duplicates`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))

        importFolder(treeUri)
        val second = importFolder(treeUri)

        assertEquals(1, folders.folders.value.size)
        assertEquals("the second import has nothing to add", 0, second.imported)
        assertEquals(1, second.alreadyInLibrary)
        assertEquals(1, library.books.value.size)
    }

    @Test
    fun `a book already in the library joins the folder rather than being skipped`() = runTest {
        // The reader collected two volumes by hand and now points at the folder that holds them.
        library.books.value = listOf(book(id = 1, uri = "content://tree/Vol 1/vol-1.epub"))
        // The database assigns ids; this keeps the fake's next id clear of the book already there.
        library.nextId = 100
        scanner.contents[treeUri] = mutableListOf(
            folderEntry("Vol 1/vol-1.epub"),
            folderEntry("Vol 2/vol-2.epub"),
        )

        val summary = importFolder(treeUri)

        assertEquals(1, summary.imported)
        assertEquals(1, summary.alreadyInLibrary)
        val adopted = library.books.value.first { it.uri.endsWith("vol-1.epub") }
        assertNotNull("the book they already had is filed under the folder", adopted.folderId)
        assertTrue("and it is the same book, not a second copy", library.books.value.size == 2)
    }

    // endregion

    // region Re-scanning

    @Test
    fun `a rescan imports only what is new`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id

        scanner.contents[treeUri] = mutableListOf(
            folderEntry("vol-1.epub"),
            folderEntry("vol-2.epub"),
        )
        val summary = rescanFolder(folderId)

        assertEquals("only the new volume is read", 1, summary.imported)
        assertEquals(2, library.books.value.size)
    }

    @Test
    fun `a rescan reports files that have gone without deleting them`() = runTest {
        scanner.contents[treeUri] = mutableListOf(
            folderEntry("vol-1.epub"),
            folderEntry("vol-2.epub"),
        )
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id

        // The user moved a volume away, or the card holding it is not mounted. Either way the app
        // cannot tell the difference, and deleting their bookmarks would be the wrong guess.
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        val summary = rescanFolder(folderId)

        assertEquals(1, summary.missing)
        assertEquals("the book itself is still there", 2, library.books.value.size)
        assertTrue("and is still filed", library.books.value.all { it.folderId == folderId })
    }

    @Test
    fun `a rescan whose grant is gone says so and imports nothing`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id

        scanner.permissionGranted = false
        val summary = rescanFolder(folderId)

        assertTrue(summary.permissionDenied)
        assertEquals(1, library.books.value.size)
    }

    @Test
    fun `a scan that hit the ceiling reports it`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        scanner.truncate = true

        val summary = importFolder(treeUri)

        assertTrue("the screen can tell the reader the scan stopped early", summary.truncated)
    }

    // endregion

    // region Renaming, moving, deleting

    @Test
    fun `renaming persists the new name`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id

        renameFolder(folderId, "  سلسلة المانجا  ")

        assertEquals("سلسلة المانجا", folders.getFolder(folderId)?.name)
    }

    @Test
    fun `an empty name is refused rather than stored`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id

        renameFolder(folderId, "   ")

        assertEquals("Manga", folders.getFolder(folderId)?.name)
    }

    @Test
    fun `deleting a folder keeps its books and un-files them`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id

        deleteFolder(folderId, deleteContents = false)

        assertNull(folders.getFolder(folderId))
        assertEquals("the books stay in the library", 1, library.books.value.size)
        assertNull("and are simply unfiled", library.books.value.single().folderId)
        assertEquals("the grant is released", listOf(treeUri), scanner.released)
    }

    @Test
    fun `deleting a folder with its contents deletes the books`() = runTest {
        scanner.contents[treeUri] = mutableListOf(
            folderEntry("vol-1.epub"),
            folderEntry("vol-2.epub"),
        )
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id

        deleteFolder(folderId, deleteContents = true)

        assertTrue("the books are gone with it", library.books.value.isEmpty())
        assertEquals("both volumes", 2, library.deleted.size)
        // Their reading positions and bookmarks go too, exactly as deleting a book anywhere does.
        assertTrue(progress.positions.value.isEmpty())
    }

    @Test
    fun `the library can be filtered to one folder`() = runTest {
        scanner.contents[treeUri] = mutableListOf(folderEntry("vol-1.epub"))
        importFolder(treeUri)
        val folderId = folders.folders.value.first().id
        library.books.value = library.books.value + book(id = 99, uri = "content://standalone.epub")

        val observeLibrary = ObserveLibraryUseCase(library, progress)
        val all = observeLibrary(sort = com.mylibrary.core.domain.model.LibrarySort.TITLE_ASC).first()
        val inFolder = observeLibrary(
            sort = com.mylibrary.core.domain.model.LibrarySort.TITLE_ASC,
            folderId = folderId,
        ).first()

        assertEquals(2, all.size)
        assertEquals(1, inFolder.size)
        assertEquals(folderId, inFolder.single().book.folderId)
    }

    // endregion

    // region Helpers

    private fun book(id: Long, uri: String) = Book(
        id = id,
        title = uri.substringAfterLast('/'),
        uri = uri,
        format = BookFormat.EPUB,
    )

    // endregion
}
