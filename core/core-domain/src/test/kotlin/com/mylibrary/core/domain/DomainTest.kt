package com.mylibrary.core.domain

import com.mylibrary.core.common.DefaultDispatcherProvider
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.ReadingPosition
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.repository.BookMetadataResult
import com.mylibrary.core.domain.usecase.ImportBooksUseCase
import com.mylibrary.core.domain.usecase.ImportCandidate
import com.mylibrary.core.domain.usecase.ObserveContinueReadingUseCase
import com.mylibrary.core.domain.usecase.ObserveLibraryUseCase
import com.mylibrary.core.domain.usecase.ReadingProgressUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format resolution, which decides whether an imported file is even accepted. */
class BookFormatTest {

    @Test
    fun `resolves every supported extension`() {
        assertEquals(BookFormat.PDF, BookFormat.fromExtension("pdf"))
        assertEquals(BookFormat.EPUB, BookFormat.fromExtension("epub"))
        assertEquals(BookFormat.TXT, BookFormat.fromExtension("txt"))
        assertEquals(BookFormat.CBZ, BookFormat.fromExtension("cbz"))
        assertEquals(BookFormat.CBR, BookFormat.fromExtension("cbr"))
    }

    @Test
    fun `resolution ignores case and a leading dot`() {
        assertEquals(BookFormat.PDF, BookFormat.fromExtension("PDF"))
        assertEquals(BookFormat.EPUB, BookFormat.fromExtension(".Epub"))
    }

    @Test
    fun `unknown and absent extensions resolve to null rather than guessing`() {
        assertNull(BookFormat.fromExtension("mobi"))
        assertNull(BookFormat.fromExtension(null))
        assertNull(BookFormat.fromExtension(""))
    }

    @Test
    fun `resolves the documented mime types`() {
        assertEquals(BookFormat.PDF, BookFormat.fromMimeType("application/pdf"))
        assertEquals(BookFormat.EPUB, BookFormat.fromMimeType("application/epub+zip"))
        assertEquals(BookFormat.TXT, BookFormat.fromMimeType("text/plain"))
        assertEquals(BookFormat.CBZ, BookFormat.fromMimeType("application/vnd.comicbook+zip"))
    }

    @Test
    fun `mime resolution tolerates a charset parameter and case`() {
        assertEquals(BookFormat.TXT, BookFormat.fromMimeType("TEXT/PLAIN; charset=utf-8"))
    }

    @Test
    fun `generic provider mime types do not resolve`() {
        // This is exactly why `ImportBooksUseCase` tries the extension first: providers mislabel
        // comic archives as a bare zip, and guessing from that would import them as something else.
        assertNull(BookFormat.fromMimeType("application/zip"))
        assertNull(BookFormat.fromMimeType("application/octet-stream"))
    }

    @Test
    fun `paged and reflowable formats are classified correctly`() {
        assertTrue(BookFormat.PDF.isPaged)
        assertTrue(BookFormat.CBZ.isPaged)
        assertTrue(BookFormat.CBR.isPaged)
        assertTrue(BookFormat.EPUB.isReflowable)
        assertTrue(BookFormat.TXT.isReflowable)

        assertTrue(!BookFormat.EPUB.isPaged)
        assertTrue(!BookFormat.PDF.isReflowable)
    }

    @Test
    fun `the picker offers every format plus the generic types providers use`() {
        BookFormat.entries.forEach { format ->
            assertTrue(
                "picker must offer ${format.fileExtension}",
                BookFormat.pickerMimeTypes.containsAll(format.mimeTypes),
            )
        }
        assertTrue(BookFormat.pickerMimeTypes.contains("application/zip"))
    }
}

/** Progress arithmetic, which the reader and the library card must agree on. */
class ReadingProgressUseCaseTest {

    private val useCase = ReadingProgressUseCase()

    @Test
    fun `first page of a hundred is one percent`() {
        assertEquals(0.01f, useCase.fromPage(0, 100), 0.0001f)
    }

    @Test
    fun `last page is complete`() {
        assertEquals(1f, useCase.fromPage(99, 100), 0.0001f)
    }

    @Test
    fun `a single page document reads as complete on its only page`() {
        assertEquals(1f, useCase.fromPage(0, 1), 0.0001f)
    }

    @Test
    fun `an empty document reports zero rather than dividing by zero`() {
        assertEquals(0f, useCase.fromPage(0, 0), 0.0001f)
        assertEquals(0f, useCase.fromChapter(0, 0), 0.0001f)
    }

    @Test
    fun `a page index past the end is clamped to complete`() {
        assertEquals(1f, useCase.fromPage(500, 100), 0.0001f)
    }

    @Test
    fun `chapter progress accounts for progress within the chapter`() {
        // Half way through the *first* of four chapters is (0 + 0.5) / 4 = 0.125, not 0.25 — the
        // within-chapter term is what makes the progress bar move while reading a long chapter
        // instead of standing still and then jumping a whole quarter of the book at the chapter end.
        assertEquals(0.125f, useCase.fromChapter(0, 4, fractionWithinChapter = 0.5f), 0.0001f)
        // And half way through the second chapter is correspondingly further along.
        assertEquals(0.375f, useCase.fromChapter(1, 4, fractionWithinChapter = 0.5f), 0.0001f)
    }
}

/** The join of books and reading progress that the library screen renders. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveLibraryUseCaseTest {

    private val library = FakeLibraryRepository()
    private val progress = FakeProgressRepository()
    private val useCase = ObserveLibraryUseCase(library, progress)

    private fun book(id: Long, title: String, favorite: Boolean = false, format: BookFormat = BookFormat.PDF) =
        Book(id = id, title = title, uri = "content://book/$id", format = format, isFavorite = favorite)

    @Test
    fun `attaches reading progress to each book`() = runTest {
        library.books.value = listOf(book(1, "أ"), book(2, "ب"))
        progress.positions.value = mapOf(2L to ReadingPosition(2, ReadingLocator.Paged(5), 0.5f))

        val items = useCase().first()

        assertEquals(2, items.size)
        assertNull(items.first { it.book.id == 1L }.position)
        assertEquals(0.5f, items.first { it.book.id == 2L }.progress!!, 0.0001f)
    }

    @Test
    fun `filters to favorites when asked`() = runTest {
        library.books.value = listOf(book(1, "أ", favorite = true), book(2, "ب"))

        val items = useCase(favoritesOnly = true).first()

        assertEquals(listOf(1L), items.map { it.book.id })
    }

    @Test
    fun `filters by format when asked`() = runTest {
        library.books.value = listOf(
            book(1, "رواية", format = BookFormat.EPUB),
            book(2, "مجلة", format = BookFormat.CBZ),
        )

        val items = useCase(formats = setOf(BookFormat.CBZ)).first()

        assertEquals(listOf(2L), items.map { it.book.id })
    }

    @Test
    fun `an empty format filter means no filter, not no results`() = runTest {
        library.books.value = listOf(book(1, "أ"), book(2, "ب"))

        assertEquals(2, useCase(formats = emptySet()).first().size)
    }

    @Test
    fun `continue reading picks the most recent unfinished book`() = runTest {
        library.books.value = listOf(
            book(1, "قديم").copy(lastOpenedAt = 100),
            book(2, "حديث").copy(lastOpenedAt = 300),
        )
        progress.positions.value = mapOf(
            1L to ReadingPosition(1, ReadingLocator.Paged(0), 0.1f),
            2L to ReadingPosition(2, ReadingLocator.Paged(0), 0.1f),
        )

        val item = ObserveContinueReadingUseCase(library, progress)().first()

        assertEquals(2L, item?.book?.id)
    }

    @Test
    fun `continue reading skips a finished book`() = runTest {
        library.books.value = listOf(
            book(1, "مكتمل").copy(lastOpenedAt = 300),
            book(2, "قيد القراءة").copy(lastOpenedAt = 100),
        )
        progress.positions.value = mapOf(
            1L to ReadingPosition(1, ReadingLocator.Paged(99), 1f),
            2L to ReadingPosition(2, ReadingLocator.Paged(5), 0.4f),
        )

        val item = ObserveContinueReadingUseCase(library, progress)().first()

        assertEquals(2L, item?.book?.id)
    }

    @Test
    fun `a book at 99_5 percent counts as finished`() {
        assertTrue(LibraryItem(book(1, "أ"), null).let { !it.isFinished })
        val nearlyDone = LibraryItem(
            book(1, "أ"),
            ReadingPosition(1, ReadingLocator.Paged(100), 0.999f),
        )
        assertTrue(nearlyDone.isFinished)
    }
}

/** Import: format detection, duplicate handling and tolerance of unreadable metadata. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportBooksUseCaseTest {

    private val library = FakeLibraryRepository()
    private val dispatchers = DefaultDispatcherProvider()

    private fun candidate(name: String, mime: String? = "application/pdf", uri: String = "content://x/$name") =
        ImportCandidate(uri = uri, displayName = name, mimeType = mime, sizeBytes = 1024)

    @Test
    fun `imports a supported file and uses the decoded metadata`() = runTest {
        val documents = FakeDocumentRepository(
            metadata = BookMetadataResult(
                title = "عنوان من الملف",
                author = "مؤلف",
                language = "ar",
                contentCount = 320,
            ),
        )
        val useCase = ImportBooksUseCase(library, documents, dispatchers)

        val summary = useCase(listOf(candidate("book.pdf")))

        assertEquals(1, summary.imported)
        val stored = library.books.value.single()
        assertEquals("عنوان من الملف", stored.title)
        assertEquals("مؤلف", stored.author)
        assertEquals(320, stored.contentCount)
    }

    @Test
    fun `falls back to the file name when metadata cannot be read`() = runTest {
        // The decoder failing on a file must not cost the user the file: it still imports.
        val useCase = ImportBooksUseCase(library, FakeDocumentRepository(), dispatchers)

        val summary = useCase(listOf(candidate("رواية.epub", mime = "application/epub+zip")))

        assertEquals(1, summary.imported)
        assertEquals("رواية", library.books.value.single().title)
    }

    @Test
    fun `resolves the format from the extension when the provider lies about the mime type`() = runTest {
        // A CBZ reported as a plain zip is the single most common real-world mislabel.
        val useCase = ImportBooksUseCase(library, FakeDocumentRepository(), dispatchers)

        useCase(listOf(candidate("comic.cbz", mime = "application/zip")))

        assertEquals(BookFormat.CBZ, library.books.value.single().format)
    }

    @Test
    fun `does not import the same uri twice`() = runTest {
        library.books.value = listOf(
            Book(id = 1, title = "موجود", uri = "content://x/book.pdf", format = BookFormat.PDF),
        )
        val useCase = ImportBooksUseCase(library, FakeDocumentRepository(), dispatchers)

        val summary = useCase(listOf(candidate("book.pdf")))

        assertEquals(0, summary.imported)
        assertEquals(1, summary.alreadyInLibrary)
        assertEquals(1, library.books.value.size)
    }

    @Test
    fun `counts unsupported files instead of failing the whole import`() = runTest {
        val documents = FakeDocumentRepository(supportedFormats = setOf(BookFormat.PDF))
        val useCase = ImportBooksUseCase(library, documents, dispatchers)

        val summary = useCase(
            listOf(
                candidate("a.pdf"),
                candidate("b.cbz", mime = "application/x-cbz"), // engine not registered for CBZ
                candidate("c.mobi", mime = null), // unknown extension and no MIME type at all
            ),
        )

        // The point of the test: one good file still imports, and the rest are *counted* rather
        // than aborting the import or disappearing silently.
        assertEquals(1, summary.imported)
        assertEquals(2, summary.unsupported)
        assertEquals(0, summary.alreadyInLibrary)
        assertEquals(3, summary.total)
    }

    @Test
    fun `an unknown extension still imports when the provider reports a supported mime type`() = runTest {
        // The mirror image of the mislabelled-CBZ case: some providers hand back no usable
        // extension (a numeric document id) but do report the right type. Format resolution tries
        // the extension first and falls back to the MIME type, so both cases work.
        val useCase = ImportBooksUseCase(library, FakeDocumentRepository(), dispatchers)

        val summary = useCase(listOf(candidate("document-1234", mime = "application/pdf")))

        assertEquals(1, summary.imported)
        assertEquals(BookFormat.PDF, library.books.value.single().format)
    }

    @Test
    fun `an empty selection is a no-op`() = runTest {
        val useCase = ImportBooksUseCase(library, FakeDocumentRepository(), dispatchers)

        val summary = useCase(emptyList())

        assertEquals(0, summary.total)
        assertTrue(library.books.value.isEmpty())
    }
}

/** Settings defaults, which a fresh install depends on. */
class ReaderSettingsTest {

    @Test
    fun `the default language is Arabic`() {
        // The app is Arabic-first by requirement; an English default would silently break that on
        // first launch, when nothing has been persisted yet.
        assertEquals(com.mylibrary.core.domain.model.AppLanguage.ARABIC, ReaderSettings.Default.language)
    }

    @Test
    fun `the default theme follows the system`() {
        assertEquals(ThemeMode.SYSTEM, ReaderSettings.Default.themeMode)
    }

    @Test
    fun `the app's own colour is the default, not the wallpaper's`() {
        // The wallpaper palette is a choice, not a default: it varies with something the reader
        // did not do in this app, and an interface that changes colour because a photograph
        // changed is not what someone opening a new app expects to find.
        assertEquals(ColorSource.TEAL, ReaderSettings.Default.colorSource)
    }

    @Test
    fun `a fresh install has not answered the colour setup`() {
        // Which is what makes a first launch show it. The store treats a settings file with
        // anything in it as already answered, so this default only ever reaches a new install.
        assertFalse(ReaderSettings.Default.setupComplete)
    }

    @Test
    fun `copy leaves unrelated fields alone`() {
        val changed = ReaderSettings.Default.copy(themeMode = ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, changed.themeMode)
        assertEquals(ReaderSettings.Default.language, changed.language)
        assertEquals(ReaderSettings.Default.fontScale, changed.fontScale, 0.0001f)
    }
}
