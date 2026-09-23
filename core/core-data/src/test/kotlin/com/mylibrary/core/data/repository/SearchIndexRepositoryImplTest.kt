package com.mylibrary.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mylibrary.core.common.DefaultDispatcherProvider
import com.mylibrary.core.data.local.MyLibraryDatabase
import com.mylibrary.core.data.local.entity.BookEntity
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.repository.IndexedChunk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The full-text index, against a real SQLite database.
 *
 * The `LIKE … ESCAPE '\'` matching and the snippet offsets are strings and arithmetic the compiler
 * cannot check, which is exactly what these tests exist for: a wrong escape turns a reader's `%` into
 * a wildcard over the whole library, and an offset that is off by the collapsed whitespace highlights
 * the wrong word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchIndexRepositoryImplTest {

    private lateinit var database: MyLibraryDatabase
    private lateinit var repository: SearchIndexRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, MyLibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SearchIndexRepositoryImpl(database.searchIndexDao(), DefaultDispatcherProvider())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertBook(title: String = "كتاب"): Long = database.bookDao().insert(
        BookEntity(
            title = title,
            author = null,
            uri = "content://book/$title",
            format = "EPUB",
            addedAt = 0,
        ),
    )

    @Test
    fun `a book is not indexed until its index is written`() = runTest {
        val bookId = insertBook()

        assertEquals(emptySet<Long>(), repository.indexedBookIds())

        // Even an empty index is a look, and marks the book so it is not opened again.
        repository.replaceIndex(bookId, emptyList())

        assertEquals(setOf(bookId), repository.indexedBookIds())
    }

    @Test
    fun `a hit carries the chunk's locator, label and a highlighted snippet`() = runTest {
        val bookId = insertBook()
        repository.replaceIndex(
            bookId,
            listOf(
                IndexedChunk(
                    label = "الفصل الأول",
                    locator = ReadingLocator.Reflowable(0, 0),
                    text = "في قديم الزمان عاش ملك عادل في مدينة بعيدة.",
                ),
            ),
        )

        val hit = repository.search("ملك", limitPerBook = 20).getValue(bookId).single()

        assertEquals(ReadingLocator.Reflowable(0, 0), hit.locator)
        assertEquals("الفصل الأول", hit.label)
        assertEquals("ملك", hit.snippet.substring(hit.matchStart, hit.matchEnd))
    }

    @Test
    fun `matching ignores case, as the title search does`() = runTest {
        val bookId = insertBook()
        repository.replaceIndex(bookId, listOf(IndexedChunk(null, ReadingLocator.Paged(3), "The Dune saga")))

        assertEquals(ReadingLocator.Paged(3), repository.search("dune", 20).getValue(bookId).single().locator)
    }

    @Test
    fun `the per-book limit is respected`() = runTest {
        val bookId = insertBook()
        repository.replaceIndex(
            bookId,
            (0 until 5).map { IndexedChunk(null, ReadingLocator.Paged(it), "الفصل $it ملك") },
        )

        assertEquals(2, repository.search("ملك", limitPerBook = 2).getValue(bookId).size)
    }

    @Test
    fun `clearing the index forgets the book`() = runTest {
        val bookId = insertBook()
        repository.replaceIndex(bookId, listOf(IndexedChunk(null, ReadingLocator.Paged(0), "نص")))

        repository.clearIndex(bookId)

        assertEquals(emptySet<Long>(), repository.indexedBookIds())
        assertTrue(repository.search("نص", 20).isEmpty())
    }

    @Test
    fun `LIKE wildcards in the query are matched literally`() = runTest {
        val bookId = insertBook()
        repository.replaceIndex(bookId, listOf(IndexedChunk(null, ReadingLocator.Paged(0), "100% sure")))

        assertEquals("a typed percent matches its own text", 1, repository.search("100%", 20).size)
        assertEquals(
            "an underscore does not stand for an arbitrary character",
            0,
            repository.search("s_re", 20).size,
        )
    }

    // region snippet building

    @Test
    fun `the snippet offsets point at the match inside the text that is shown`() {
        val text = "a".repeat(100) + "NEEDLE" + "b".repeat(100)

        val snippet = buildSnippet(text, "needle")!!

        assertTrue("the match is inside the snippet", snippet.matchEnd <= snippet.text.length)
        assertEquals("NEEDLE", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue("an ellipsis marks the truncation", snippet.text.startsWith("…"))
    }

    @Test
    fun `whitespace is collapsed before the match is located`() {
        val snippet = buildSnippet("line one\n\nline two   ملك here", "ملك")!!

        assertTrue("no newline survives into the snippet", '\n' !in snippet.text)
        assertEquals("ملك", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun `a query that does not occur has no snippet`() {
        assertNull(buildSnippet("some text", "absent"))
        assertNull(buildSnippet("some text", ""))
    }

    // endregion
}
