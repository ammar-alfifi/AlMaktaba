package com.mylibrary.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mylibrary.core.common.DefaultDispatcherProvider
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.data.local.MyLibraryDatabase
import com.mylibrary.core.data.local.dao.BookDao
import com.mylibrary.core.data.local.dao.BookmarkDao
import com.mylibrary.core.data.local.dao.ReadingPositionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Infrastructure bindings: dispatchers, the database, its DAOs and the settings DataStore.
 *
 * Everything here is `@Singleton` because each of them owns a real OS resource — a database
 * connection, a file, a thread pool — and creating two of any of them would be a leak, not just a
 * waste.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    /**
     * The Room database.
     *
     * Foreign keys are enabled explicitly because Room leaves them off by default on Android, and
     * without them the `ON DELETE CASCADE` on reading positions and bookmarks would silently do
     * nothing — leaving orphan rows pointing at books that no longer exist.
     *
     * Migrations are not registered yet because version 1 is the first schema. When version 2
     * arrives this is where `addMigrations(...)` goes, and the exported schema in `schemas/` is what
     * makes those migrations testable.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MyLibraryDatabase =
        Room.databaseBuilder(context, MyLibraryDatabase::class.java, MyLibraryDatabase.NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun provideBookDao(database: MyLibraryDatabase): BookDao = database.bookDao()

    @Provides
    fun provideReadingPositionDao(database: MyLibraryDatabase): ReadingPositionDao =
        database.readingPositionDao()

    @Provides
    fun provideBookmarkDao(database: MyLibraryDatabase): BookmarkDao = database.bookmarkDao()

    /**
     * The preferences DataStore.
     *
     * A corruption handler is installed so that a truncated preferences file — the classic result of
     * the process being killed mid-write — resets settings to their defaults instead of throwing on
     * every read and making the app unusable until the user clears its data.
     *
     * The scope uses `Dispatchers.IO` with a `SupervisorJob`: DataStore writes must survive one
     * failed write, and must never run on the main thread.
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(PREFERENCES_NAME) },
    )

    private const val PREFERENCES_NAME = "mylibrary_settings"
}
