package com.mylibrary.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mylibrary.core.data.local.dao.BookDao
import com.mylibrary.core.data.local.dao.BookmarkDao
import com.mylibrary.core.data.local.dao.FolderDao
import com.mylibrary.core.data.local.dao.ReadingPositionDao
import com.mylibrary.core.data.local.dao.SearchIndexDao
import com.mylibrary.core.data.local.entity.BookEntity
import com.mylibrary.core.data.local.entity.BookmarkEntity
import com.mylibrary.core.data.local.entity.FolderEntity
import com.mylibrary.core.data.local.entity.IndexedBookEntity
import com.mylibrary.core.data.local.entity.ReadingPositionEntity
import com.mylibrary.core.data.local.entity.SearchIndexEntity

/**
 * MyLibrary's local store.
 *
 * Version 2 added device folders — a `folders` table and a nullable `books.folderId`. Every change
 * to an entity from here on requires a bumped version and a [androidx.room.migration.Migration]: the
 * library is the user's own data and silently dropping it on upgrade is not an acceptable failure
 * mode. Schemas are exported to the module's `schemas/` directory, so a migration can be tested
 * against a real previous version — and Room validates the result on open, so a migration that does
 * not reproduce the generated schema fails loudly rather than quietly drifting.
 *
 * There are no `@TypeConverters`: every column is a primitive or a `String`. Enums are stored by
 * name and the reading locator is flattened into columns — both are deliberate, and both mean a
 * schema change shows up as a migration instead of as data that no longer parses.
 */
@Database(
    entities = [
        BookEntity::class,
        ReadingPositionEntity::class,
        BookmarkEntity::class,
        FolderEntity::class,
        SearchIndexEntity::class,
        IndexedBookEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MyLibraryDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun readingPositionDao(): ReadingPositionDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun folderDao(): FolderDao

    abstract fun searchIndexDao(): SearchIndexDao

    companion object {
        const val NAME = "mylibrary.db"
    }
}
