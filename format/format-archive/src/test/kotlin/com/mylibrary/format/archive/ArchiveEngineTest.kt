package com.mylibrary.format.archive

import com.google.common.truth.Truth.assertThat
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.model.PageSize
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The engine's own contract: what it claims to support, what it opens, and how it fails. */
class ArchiveEngineTest {

    @get:Rule
    val cache = TemporaryFolder()

    private lateinit var engine: ArchiveEngine

    @Before
    fun setUp() {
        // A real cache directory, so the engine is built exactly as the app builds it. Nothing is
        // written there by a CBZ test, which is itself part of the point.
        engine = ArchiveEngine(cacheDirectory = cache.root)
    }

    @Test
    fun `supports both comic containers and nothing else`() {
        assertThat(engine.supports(BookFormat.CBZ)).isTrue()
        assertThat(engine.supports(BookFormat.CBR)).isTrue()

        assertThat(engine.supports(BookFormat.PDF)).isFalse()
        assertThat(engine.supports(BookFormat.EPUB)).isFalse()
        assertThat(engine.supports(BookFormat.TXT)).isFalse()
    }

    @Test
    fun `opens a cbz with one page per image`() = runBlocking {
        val document = open(cbzSource())

        assertThat(document.pageCount).isEqualTo(10)
        assertThat(document.format).isEqualTo(BookFormat.CBZ)
        assertThat(document.metadata.title).isEqualTo("book")

        document.close()
    }

    @Test
    fun `describes itself as an image document with no text layer`() = runBlocking {
        val document = open(cbzSource())

        assertThat(document.capabilities.canRenderPages).isTrue()
        assertThat(document.capabilities.canSearch).isFalse()
        assertThat(document.capabilities.canExtractText).isFalse()
        assertThat(document.capabilities.hasOutline).isFalse()
        assertThat(document.outline).isEmpty()

        // A comic page has no text: not an empty string, which would let the UI offer a search that
        // can never match anything.
        assertThat(document.pageText(0)).isNull()
        assertThat(document.search("anything")).isEmpty()

        document.close()
    }

    @Test
    fun `sniffs a rar that is wearing a cbz extension`() = runBlocking {
        // The zip reader would see no entries in this file at all, so "corrupt" rather than "empty"
        // is only reachable by having gone to the rar reader.
        val mislabelledRar = "Rar!".toByteArray() + "this is not really a rar archive".toByteArray()
        val source = InMemoryDocumentSource(mislabelledRar, "mislabelled.cbz", BookFormat.CBZ)

        val error = openError(source)

        assertThat(error).isInstanceOf(AppError.CorruptDocument::class.java)
        // The rar path copies the source into the cache directory, because junrar needs a file. A
        // document that fails to open must not leave that copy behind.
        assertThat(cache.root.listFiles()).isEmpty()
    }

    @Test
    fun `sniffs a zip that is wearing a cbr extension`() = runBlocking {
        val source = InMemoryDocumentSource(TestComics.cbz(), "mislabelled.cbr", BookFormat.CBR)

        val document = open(source)

        assertThat(document.pageCount).isEqualTo(10)
        assertThat(document.format).isEqualTo(BookFormat.CBZ)

        document.close()
    }

    @Test
    fun `reports a file that is not an archive as empty or corrupt`() = runBlocking {
        val source = InMemoryDocumentSource(
            "an ordinary text file, picked by mistake".toByteArray(),
            "notes.cbz",
            BookFormat.CBZ,
        )

        val error = openError(source)

        assertThat(error is AppError.EmptyDocument || error is AppError.CorruptDocument).isTrue()
    }

    @Test
    fun `reports an archive with no images as empty`() = runBlocking {
        val source = InMemoryDocumentSource(
            TestComics.cbz(listOf("readme.txt" to "no pages here".toByteArray())),
            "notes.cbz",
            BookFormat.CBZ,
        )

        assertThat(openError(source)).isEqualTo(AppError.EmptyDocument)
    }

    @Test
    fun `closing twice is safe, and rendering after a close fails instead of crashing`() = runBlocking {
        val document = open(cbzSource())

        document.close()
        document.close()

        val result = document.renderPage(
            PageRenderRequest(pageIndex = 0, targetWidthPx = 1080, targetHeightPx = 1920)
        )

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error).isInstanceOf(AppError.FileAccess::class.java)
    }

    @Test
    fun `asks for a page outside the document and gets a caller error`() = runBlocking {
        val document = open(cbzSource())

        val failure = runCatching { document.pageSize(10) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IndexOutOfBoundsException::class.java)

        document.close()
    }

    @Test
    fun `reports an unreadable page as unknown-sized rather than throwing`() = runBlocking {
        // On a plain JVM unit test there is no BitmapFactory, so no page header can be parsed here.
        // What is under test is the contract around that: a page whose size cannot be learnt comes
        // back as an unknown size instead of as an exception thrown into the reader's scroll loop.
        val document = open(cbzSource())

        assertThat(document.pageSize(0)).isEqualTo(PageSize(0, 0))

        document.close()
    }

    private fun cbzSource(name: String = "book.cbz"): DocumentSource =
        InMemoryDocumentSource(TestComics.cbzWithCruft(), name, BookFormat.CBZ)

    private suspend fun open(source: DocumentSource): PagedDocument =
        when (val result = engine.open(source)) {
            is AppResult.Success -> result.data as PagedDocument
            is AppResult.Failure -> throw AssertionError("Expected the document to open, but got ${result.error}")
        }

    private suspend fun openError(source: DocumentSource): AppError =
        when (val result = engine.open(source)) {
            is AppResult.Success -> throw AssertionError("Expected opening to fail, but it returned a document")
            is AppResult.Failure -> result.error
        }
}
