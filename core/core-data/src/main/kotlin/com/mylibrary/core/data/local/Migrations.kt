package com.mylibrary.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 → 2: device folders.
 *
 * Adds the `folders` table and a nullable `books.folderId`, in the exact shape Room's own generator
 * would produce — the statements below are copied from what it writes for a nullable column without
 * a default, because Room validates the schema after migrating, in production as well as in tests. A
 * `DEFAULT NULL` written here, for instance, would store the *text* `NULL` as the column's default
 * and fail that comparison.
 *
 * **Why `books.folderId` has no foreign key, when the schema obviously wants one.** SQLite cannot add
 * a constraint with `ALTER TABLE`: a foreign key means rebuilding the table — create, copy, drop,
 * rename — and `books` is the *parent* of `reading_positions` and `bookmarks`, both `ON DELETE
 * CASCADE`. Dropping it performs an implicit `DELETE FROM books`, and with foreign keys enabled that
 * cascades: every reading position and every bookmark in the user's library would be deleted by an
 * upgrade. Turning foreign keys off for the duration does not help either, because the pragma is a
 * no-op inside a transaction and Room runs migrations inside one.
 *
 * So the association is enforced where it can be: `FolderRepositoryImpl.deleteFolder` clears the
 * books' folder and deletes the row in a single transaction, which is the same place the "match on
 * URI and update rather than replace" rule already lives.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `folders` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`uri` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "`lastScannedAt` INTEGER)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_uri` ON `folders` (`uri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folders_addedAt` ON `folders` (`addedAt`)")

        db.execSQL("ALTER TABLE `books` ADD COLUMN `folderId` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_folderId` ON `books` (`folderId`)")
    }
}

/**
 * Version 2 → 3: the full-text index behind "search inside books".
 *
 * Two child tables, and deliberately no change to `books`. A foreign key on a *child* is safe: the
 * cascade runs when a book is deleted, which is exactly what should happen to its index, and unlike
 * the folder association there is no parent table to rebuild — so the hazard the 1 → 2 migration
 * documents does not arise here.
 *
 * The statements are written in the shape Room's generator produces, because Room validates the
 * schema after migrating in production as well as in tests.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `search_index` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, " +
                "`label` TEXT, " +
                "`locator` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_search_index_bookId` " +
                "ON `search_index` (`bookId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `indexed_books` (" +
                "`bookId` INTEGER NOT NULL, " +
                "`indexedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bookId`), " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
    }
}

/** Every migration the database needs, in order. Registered by `DataModule`. */
internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
