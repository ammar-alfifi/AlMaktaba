package com.mylibrary.core.data.di

import com.mylibrary.core.data.repository.BookmarkRepositoryImpl
import com.mylibrary.core.data.repository.DocumentRepositoryImpl
import com.mylibrary.core.data.repository.LibraryRepositoryImpl
import com.mylibrary.core.data.repository.ReadingProgressRepositoryImpl
import com.mylibrary.core.data.repository.SettingsRepositoryImpl
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.DocumentRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.repository.ReadingProgressRepository
import com.mylibrary.core.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds each repository interface to its implementation.
 *
 * This module is what makes the dependency rule enforceable rather than aspirational: feature
 * modules depend on `:core:core-domain`, which declares only the interfaces, so the compiler — not
 * a convention — is what stops a ViewModel from reaching into a DAO.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindReadingProgressRepository(
        impl: ReadingProgressRepositoryImpl,
    ): ReadingProgressRepository

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository
}
