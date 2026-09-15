package com.mylibrary.core.data.di

import android.content.Context
import com.mylibrary.core.domain.engine.DocumentEngine
import com.mylibrary.format.archive.ArchiveEngine
import com.mylibrary.format.epub.EpubEngine
import com.mylibrary.format.pdf.PdfEngine
import com.mylibrary.format.text.TextEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.multibindings.IntoSet
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Registers every decoder.
 *
 * This is the single place in MyLibrary where the `:format:*` modules are named. They are injected
 * as a `Set<DocumentEngine>`, so a new format is added by writing one `@Provides @IntoSet` method
 * here and nothing else in the app changes — no `when (format)` and no registry to keep in sync.
 *
 * The engines are constructed by hand rather than annotated with `@Inject` on purpose: that keeps
 * the decoder modules free of any dependency-injection framework, which is what lets them be tested
 * as plain classes and keeps Dagger out of the code that parses untrusted files.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    @IntoSet
    fun providePdfEngine(@ApplicationContext context: Context): DocumentEngine = PdfEngine(context)

    @Provides
    @Singleton
    @IntoSet
    fun provideEpubEngine(): DocumentEngine = EpubEngine()

    @Provides
    @Singleton
    @IntoSet
    fun provideTextEngine(): DocumentEngine = TextEngine()

    @Provides
    @Singleton
    @IntoSet
    fun provideArchiveEngine(@ApplicationContext context: Context): DocumentEngine = ArchiveEngine(context)
}
