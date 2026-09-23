package com.mylibrary.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.mylibrary.core.common.DefaultDispatcherProvider
import com.mylibrary.core.data.local.entity.FolderEntity
import com.mylibrary.core.data.local.entity.IndexedBookEntity
import com.mylibrary.core.data.local.entity.SearchIndexEntity
import com.mylibrary.core.data.repository.FolderRepositoryImpl
import com.mylibrary.core.domain.model.Folder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The version 1 → 2 upgrade, against a real version 1 database.
 *
 * This test exists because nothing else can catch the failure it guards. The other database tests
 * build a fresh schema and never migrate anything, and the app's startup smoke test does the same —
 * so a migration that does not reproduce Room's generated schema would pass every test in the
 * project and then throw on the first launch after an upgrade, on the only copy of the user's
 * library that exists.
 *
 * It works without `MigrationTestHelper`: the v1 database is created here from the *frozen* DDL that
 * `schemas/…/1.json` records, and Room then opens it with the migration registered. Room validates
 * the resulting schema against its own expectations on open and throws
 * `Migration didn't properly handle…` otherwise — which is a stronger assertion than comparing two
 * descriptions of a table, because it is the same check the app performs in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-test-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `upgrading from version 1 keeps every book, position and bookmark`() = runTest {
        createVersion1Database()

        val database = Room.databaseBuilder(context, MyLibraryDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        // Opening is the assertion: Room runs the migration and then validates the schema.
        val bookDao = database.bookDao()
        assertEquals("the upgrade is recorded", 3, database.openHelper.readableDatabase.version)

        val books = bookDao.getById(1L)
        assertNotNull("a book survives the upgrade", books)
        assertEquals("كتاب قديم", books!!.title)
        assertNull("and has no folder until one is added", books.folderId)

        val position = database.readingPositionDao().getByBook(1L)
        assertNotNull("a reading position survives", position)
        assertEquals("its place in the book survives with it", 4, position!!.locatorIndex)

        val bookmarks = database.bookmarkDao().observeAll().first()
        assertEquals("a bookmark survives", 1, bookmarks.size)

        database.close()
    }

    @Test
    fun `a folder can be added and assigned after the upgrade`() = runTest {
        createVersion1Database()

        val database = Room.databaseBuilder(context, MyLibraryDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val folderDao = database.folderDao()
        val folderId = folderDao.insert(
            FolderEntity(uri = "content://tree/comics", name = "المجلدات", addedAt = 1_000L),
        )
        database.bookDao().assignFolder(listOf(1L), folderId)

        val stored = folderDao.getById(folderId)
        assertNotNull(stored)
        assertEquals("المجلدات", stored!!.name)
        assertEquals(listOf(1L), database.bookDao().idsInFolder(folderId))
        assertEquals(folderId, database.bookDao().getById(1L)?.folderId)

        database.close()
    }

    @Test
    fun `the folder URI is unique, so the same directory cannot be added twice`() = runTest {
        createVersion1Database()

        val database = Room.databaseBuilder(context, MyLibraryDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val folderDao = database.folderDao()
        folderDao.insert(FolderEntity(uri = "content://tree/comics", name = "Comics", addedAt = 1L))

        val duplicate = runCatching {
            folderDao.insert(FolderEntity(uri = "content://tree/comics", name = "Again", addedAt = 2L))
        }

        assertTrue("the unique index rejects a second row for the same tree", duplicate.isFailure)
        database.close()
    }

    @Test
    fun `removing a folder un-files its books rather than losing them`() = runTest {
        createVersion1Database()

        val database = Room.databaseBuilder(context, MyLibraryDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val repository = FolderRepositoryImpl(
            database = database,
            folderDao = database.folderDao(),
            bookDao = database.bookDao(),
            dispatchers = DefaultDispatcherProvider(),
        )

        val folderId = repository.saveFolder(
            Folder(uri = "content://tree/manga", name = "Manga"),
        )
        repository.assignBooks(listOf(1L), folderId)
        assertEquals(listOf(1L), repository.bookIdsInFolder(folderId))

        repository.deleteFolder(folderId)

        assertNull("the folder is gone", repository.getFolder(folderId))
        assertNotNull("but the book is still in the library", database.bookDao().getById(1L))
        assertNull("and is simply no longer filed", database.bookDao().getById(1L)?.folderId)

        database.close()
    }

    @Test
    fun `the search index is created by the upgrade and follows the book`() = runTest {
        createVersion1Database()

        val database = Room.databaseBuilder(context, MyLibraryDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val dao = database.searchIndexDao()
        dao.insertChunks(
            listOf(SearchIndexEntity(bookId = 1L, label = null, locator = "p0", text = "مرحبا بالعالم")),
        )
        dao.markIndexed(IndexedBookEntity(bookId = 1L, indexedAt = 1L))

        assertEquals("the book is marked as indexed", setOf(1L), dao.indexedBookIds().toSet())
        assertEquals("its text is searchable", 1, dao.searchChunks("%بالعالم%", 10).size)

        // The index is a child of the book, so deleting the book takes it with it.
        database.bookDao().deleteByIds(listOf(1L))

        assertEquals("no chunks survive the book", 0, dao.searchChunks("%بالعالم%", 10).size)
        assertEquals("and it is no longer marked indexed", emptyList<Long>(), dao.indexedBookIds())

        database.close()
    }

    /**
     * Builds the version 1 database.
     *
     * The DDL below is copied verbatim from `schemas/…/1.json` and must never be edited: it is the
     * schema as it actually shipped, which is the only thing a migration can be tested against.
     */
    private fun createVersion1Database() {
        context.deleteDatabase(databaseName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    VERSION_1_SCHEMA.forEach { statement -> db.execSQL(statement) }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, author, uri, format, sizeBytes, coverPath, contentCount, " +
                    "language, isFavorite, addedAt, lastOpenedAt) " +
                    "VALUES (1, 'كتاب قديم', 'مؤلف', 'content://book/1', 'PDF', 1024, NULL, NULL, " +
                    "'ar', 1, 100, NULL)",
            )
            db.execSQL(
                "INSERT INTO reading_positions (bookId, locatorType, locatorIndex, locatorOffset, " +
                    "percent, updatedAt, excerpt) VALUES (1, 'PAGED', 4, 0, 0.25, 200, 'مقتطف')",
            )
            db.execSQL(
                "INSERT INTO bookmarks (id, bookId, locatorType, locatorIndex, locatorOffset, label, " +
                    "excerpt, note, colorArgb, createdAt) " +
                    "VALUES (1, 1, 'PAGED', 4, 0, 'صفحة 5', 'مقتطف', NULL, NULL, 300)",
            )
        }
    }

    private companion object {
        val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `uri` TEXT NOT NULL, `format` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `coverPath` TEXT, `contentCount` INTEGER, `language` TEXT, `isFavorite` INTEGER NOT NULL DEFAULT 0, `addedAt` INTEGER NOT NULL, `lastOpenedAt` INTEGER)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_books_uri` ON `books` (`uri`)",
            "CREATE INDEX IF NOT EXISTS `index_books_addedAt` ON `books` (`addedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_books_lastOpenedAt` ON `books` (`lastOpenedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_books_title` ON `books` (`title`)",
            "CREATE TABLE IF NOT EXISTS `reading_positions` (`bookId` INTEGER NOT NULL, `locatorType` TEXT NOT NULL, `locatorIndex` INTEGER NOT NULL, `locatorOffset` INTEGER NOT NULL, `percent` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `excerpt` TEXT, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_reading_positions_updatedAt` ON `reading_positions` (`updatedAt`)",
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `locatorType` TEXT NOT NULL, `locatorIndex` INTEGER NOT NULL, `locatorOffset` INTEGER NOT NULL, `label` TEXT, `excerpt` TEXT, `note` TEXT, `colorArgb` INTEGER, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)",
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_createdAt` ON `bookmarks` (`createdAt`)",
        )
    }
}
