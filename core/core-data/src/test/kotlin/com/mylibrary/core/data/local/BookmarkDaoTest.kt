package com.mylibrary.core.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mylibrary.core.data.local.entity.BookEntity
import com.mylibrary.core.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bookmarks and highlights, against real SQLite.
 *
 * A highlight is a bookmark with a colour, and a note is a bookmark with text, so the only thing
 * that can go wrong is the persistence of those two nullable columns — which is exactly what this
 * pins: that they survive a write and a read, and that clearing them is stored as clearing them
 * rather than as leaving the old value behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkDaoTest {

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

    private suspend fun insertBook(): Long = database.bookDao().insert(
        BookEntity(title = "كتاب", author = null, uri = "content://book/1", format = "EPUB", addedAt = 0),
    )

    private fun bookmark(bookId: Long, note: String? = null, colorArgb: Int? = null) = BookmarkEntity(
        bookId = bookId,
        locatorType = "REFLOWABLE",
        locatorIndex = 2,
        locatorOffset = 40,
        label = "الفصل الثاني",
        excerpt = "مقتطف",
        note = note,
        colorArgb = colorArgb,
        createdAt = 1L,
    )

    @Test
    fun `a note and a highlight colour survive a round trip`() = runTest {
        val bookId = insertBook()
        database.bookmarkDao().insert(bookmark(bookId, note = "ملاحظتي", colorArgb = 0xFFFFF176.toInt()))

        val stored = database.bookmarkDao().observeByBook(bookId).first().single()

        assertEquals("ملاحظتي", stored.note)
        assertEquals(0xFFFFF176.toInt(), stored.colorArgb)
    }

    @Test
    fun `clearing a note and colour is stored as cleared`() = runTest {
        val bookId = insertBook()
        database.bookmarkDao().insert(bookmark(bookId, note = "ملاحظة", colorArgb = 0xFFA5D6A7.toInt()))
        val stored = database.bookmarkDao().observeByBook(bookId).first().single()

        database.bookmarkDao().update(stored.copy(note = null, colorArgb = null))

        val updated = database.bookmarkDao().observeByBook(bookId).first().single()
        assertNull("the note is gone", updated.note)
        assertNull("the colour is gone", updated.colorArgb)
    }
}
