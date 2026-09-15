package com.mylibrary.core.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mylibrary.core.data.local.entity.BookEntity
import com.mylibrary.core.data.local.entity.BookmarkEntity
import com.mylibrary.core.data.local.entity.ReadingPositionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO tests against a real SQLite database.
 *
 * These run on the JVM under Robolectric but execute the *actual* generated SQL — including the
 * hand-written `LIKE … ESCAPE '\'` filters and the five `ORDER BY` clauses. That matters because
 * those queries are strings: the compiler cannot check them, and a typo or a wrong collation would
 * otherwise only show up on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDaoTest {

    private lateinit var database: MyLibraryDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, MyLibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun book(
        title: String,
        author: String? = null,
        uri: String = "content://book/$title",
        addedAt: Long = 0,
        lastOpenedAt: Long? = null,
        format: String = "PDF",
    ) = BookEntity(
        title = title,
        author = author,
        uri = uri,
        format = format,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
    )

    @Test
    fun `inserts and reads a book back`() = runTest {
        val dao = database.bookDao()
        val id = dao.insert(book("كتاب", "مؤلف"))

        val stored = dao.getById(id)

        assertEquals("كتاب", stored?.title)
        assertEquals("مؤلف", stored?.author)
        assertNull(stored?.lastOpenedAt)
    }

    @Test
    fun `the uri index rejects a duplicate book`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("أول", uri = "content://same"))

        val failure = runCatching { dao.insert(book("ثانٍ", uri = "content://same")) }

        // The uniqueness has to be enforced by the schema, not only by the import path checking
        // first: two concurrent imports would both pass that check.
        assertTrue("expected the unique uri index to reject the duplicate", failure.isFailure)
    }

    @Test
    fun `the empty search pattern matches every book`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("أ"))
        dao.insert(book("ب"))
        dao.insert(book("ج"))

        assertEquals(3, dao.observeRecentlyAdded("%%").first().size)
    }

    @Test
    fun `title search matches a substring case-insensitively`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("The Hobbit"))
        dao.insert(book("Other"))
        dao.insert(book("حبوب"))

        assertEquals(1, dao.observeRecentlyAdded("%hobbit%").first().size)
        assertEquals(1, dao.observeRecentlyAdded("%حبوب%").first().size)
        assertEquals(3, dao.observeRecentlyAdded("%%").first().size)
    }

    @Test
    fun `a book with no author still matches the empty pattern`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("بلا مؤلف", author = null))

        // `NULL LIKE '%%'` is NULL, not true — without IFNULL in the query this row would vanish
        // from the library entirely.
        assertEquals(1, dao.observeRecentlyAdded("%%").first().size)
    }

    @Test
    fun `an escaped percent sign searches literally`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("100% Real"))
        dao.insert(book("Other"))

        // The repository escapes user input before wrapping it, so a typed '%' is a literal.
        assertEquals(1, dao.observeRecentlyAdded("%100\\%%").first().size)
    }

    @Test
    fun `sorting by title ascending orders Arabic and Latin titles`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("ثالث"))
        dao.insert(book("أول"))
        dao.insert(book("ثانٍ"))

        val titles = dao.observeTitleAscending("%%").first().map { it.title }

        // The Arabic block is laid out in alphabetical order in Unicode, so a binary comparison
        // already yields the expected sequence without a custom collation: أ is U+0623 and ث is
        // U+062B, so أول comes first; between the two ث words the second letter decides, and
        // ل (U+0644) precedes ن (U+0646), so ثالث precedes ثانٍ.
        assertEquals(listOf("أول", "ثالث", "ثانٍ"), titles)
    }

    @Test
    fun `sorting by title descending reverses it`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("أول"))
        dao.insert(book("ثالث"))

        assertEquals(listOf("ثالث", "أول"), dao.observeTitleDescending("%%").first().map { it.title })
    }

    @Test
    fun `recently read puts opened books first and unopened ones last`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("لم يفتح", addedAt = 100))
        dao.insert(book("قرئ قديما", addedAt = 200, lastOpenedAt = 500))
        dao.insert(book("قرئ حديثا", addedAt = 300, lastOpenedAt = 900))

        val titles = dao.observeRecentlyRead("%%").first().map { it.title }

        assertEquals(listOf("قرئ حديثا", "قرئ قديما", "لم يفتح"), titles)
    }

    @Test
    fun `sorting by author falls back to the title for an authorless book`() = runTest {
        val dao = database.bookDao()
        dao.insert(book(title = "ب", author = "أحمد"))
        dao.insert(book(title = "أ", author = null))
        dao.insert(book(title = "ج", author = "زينب"))

        val sorted = dao.observeByAuthor("%%").first()

        // An authorless book sorts by its own title rather than clumping at one end, and because
        // that title ("أ") is a prefix of "أحمد" it sorts immediately before it.
        assertEquals(listOf(null, "أحمد", "زينب"), sorted.map { it.author })
        assertEquals(listOf("أ", "ب", "ج"), sorted.map { it.title })
    }

    @Test
    fun `marking a book opened and favourited updates only that book`() = runTest {
        val dao = database.bookDao()
        val first = dao.insert(book("أ", uri = "content://a"))
        val second = dao.insert(book("ب", uri = "content://b"))

        dao.markOpened(first, openedAt = 1234)
        dao.setFavorite(first, isFavorite = true)

        assertEquals(1234L, dao.getById(first)?.lastOpenedAt)
        assertTrue(dao.getById(first)?.isFavorite == true)
        assertNull(dao.getById(second)?.lastOpenedAt)
        assertTrue(dao.getById(second)?.isFavorite == false)
    }

    @Test
    fun `count and distinct formats report the library's contents`() = runTest {
        val dao = database.bookDao()
        dao.insert(book("أ", uri = "content://a", format = "PDF"))
        dao.insert(book("ب", uri = "content://b", format = "EPUB"))
        dao.insert(book("ج", uri = "content://c", format = "PDF"))

        assertEquals(3, dao.observeCount().first())
        assertEquals(setOf("PDF", "EPUB"), dao.observeFormats().first().toSet())
    }

    @Test
    fun `deleting a book cascades to its position and bookmarks`() = runTest {
        val dao = database.bookDao()
        val bookId = dao.insert(book("أ", uri = "content://a"))
        database.readingPositionDao().upsert(
            ReadingPositionEntity(bookId, "PAGED", 5, 0, 0.1f, updatedAt = 1),
        )
        database.bookmarkDao().insert(
            BookmarkEntity(bookId = bookId, locatorType = "PAGED", locatorIndex = 5, createdAt = 1),
        )

        dao.deleteByIds(listOf(bookId))

        assertNull(database.readingPositionDao().getByBook(bookId))
        assertTrue(database.bookmarkDao().observeByBook(bookId).first().isEmpty())
    }

    @Test
    fun `a reading position is replaced rather than duplicated`() = runTest {
        val dao = database.readingPositionDao()
        val bookId = database.bookDao().insert(book("أ"))

        dao.upsert(ReadingPositionEntity(bookId, "PAGED", 1, 0, 0.1f, updatedAt = 1))
        dao.upsert(ReadingPositionEntity(bookId, "PAGED", 50, 0, 0.5f, updatedAt = 2))

        val stored = dao.getByBook(bookId)
        assertEquals(50, stored?.locatorIndex)
        assertEquals(0.5f, stored?.percent ?: 0f, 0.0001f)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun `deleting stale positions keeps recent ones`() = runTest {
        val dao = database.readingPositionDao()
        val old = database.bookDao().insert(book("قديم", uri = "content://old"))
        val fresh = database.bookDao().insert(book("حديث", uri = "content://new"))

        dao.upsert(ReadingPositionEntity(old, "PAGED", 1, 0, 0.1f, updatedAt = 100))
        dao.upsert(ReadingPositionEntity(fresh, "PAGED", 1, 0, 0.1f, updatedAt = 900))

        dao.deleteOlderThan(500)

        assertNull(dao.getByBook(old))
        assertNotNull(dao.getByBook(fresh))
    }

    @Test
    fun `bookmarks are returned in document order`() = runTest {
        val dao = database.bookmarkDao()
        val bookId = database.bookDao().insert(book("أ"))

        dao.insert(BookmarkEntity(bookId = bookId, locatorType = "PAGED", locatorIndex = 90, createdAt = 1))
        dao.insert(BookmarkEntity(bookId = bookId, locatorType = "PAGED", locatorIndex = 3, createdAt = 2))
        dao.insert(BookmarkEntity(bookId = bookId, locatorType = "PAGED", locatorIndex = 40, createdAt = 3))

        // Ordered by position in the book, not by when the bookmark was made: a bookmarks panel
        // that lists them out of document order is unusable for navigation.
        assertEquals(
            listOf(3, 40, 90),
            dao.observeByBook(bookId).first().map { it.locatorIndex },
        )
    }

    @Test
    fun `a bookmark round-trips its note, colour and excerpt`() = runTest {
        val dao = database.bookmarkDao()
        val bookId = database.bookDao().insert(book("أ"))

        val id = dao.insert(
            BookmarkEntity(
                bookId = bookId,
                locatorType = "REFLOWABLE",
                locatorIndex = 2,
                locatorOffset = 140,
                label = "الفصل الثاني",
                excerpt = "مقتطف",
                note = "ملاحظة",
                colorArgb = -0x1000,
                createdAt = 42,
            ),
        )

        val stored = dao.observeByBook(bookId).first().single()
        assertEquals(id, stored.id)
        assertEquals(140, stored.locatorOffset)
        assertEquals("ملاحظة", stored.note)
        assertEquals(-0x1000, stored.colorArgb)
    }
}
