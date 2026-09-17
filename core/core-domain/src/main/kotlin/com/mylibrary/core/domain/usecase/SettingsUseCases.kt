package com.mylibrary.core.domain.usecase

import com.mylibrary.core.domain.model.AppFont
import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderLayout
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The current settings, observed by every screen that renders theme, language or layout. */
class ObserveSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke(): Flow<ReaderSettings> = settingsRepository.settings
}

/**
 * Changes one or more settings.
 *
 * Each setter takes a single value rather than exposing a copy-the-whole-object API: the reader's
 * font-size slider and the settings screen's theme picker then cannot overwrite each other, because
 * both apply a transform to whatever the current value is.
 */
class UpdateSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend fun setThemeMode(mode: ThemeMode) = settingsRepository.update { it.copy(themeMode = mode) }

    suspend fun setDynamicColor(enabled: Boolean) = settingsRepository.update { it.copy(dynamicColor = enabled) }

    suspend fun setLanguage(language: AppLanguage) = settingsRepository.update { it.copy(language = language) }

    suspend fun setViewMode(mode: ViewMode) = settingsRepository.update { it.copy(viewMode = mode) }

    suspend fun setLibrarySort(sort: LibrarySort) = settingsRepository.update { it.copy(librarySort = sort) }

    suspend fun setReaderFont(font: ReaderFont) = settingsRepository.update { it.copy(readerFont = font) }

    suspend fun setFontScale(scale: Float) =
        settingsRepository.update { it.copy(fontScale = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)) }

    suspend fun setLineHeightScale(scale: Float) =
        settingsRepository.update { it.copy(lineHeightScale = scale.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)) }

    suspend fun setPageFitMode(mode: PageFitMode) = settingsRepository.update { it.copy(pageFitMode = mode) }

    suspend fun setReadingDirection(direction: ReadingDirection) =
        settingsRepository.update { it.copy(readingDirection = direction) }

    suspend fun setKeepScreenOn(enabled: Boolean) = settingsRepository.update { it.copy(keepScreenOn = enabled) }

    suspend fun setShowProgressIndicator(enabled: Boolean) =
        settingsRepository.update { it.copy(showProgressIndicator = enabled) }

    suspend fun setLayout(layout: ReaderLayout) = settingsRepository.update { it.copy(layout = layout) }

    suspend fun setTapToTurnPages(enabled: Boolean) =
        settingsRepository.update { it.copy(tapToTurnPages = enabled) }

    suspend fun setPageTurnEffect(effect: PageTurnEffect) =
        settingsRepository.update { it.copy(pageTurnEffect = effect) }

    suspend fun setBubbleZoom(enabled: Boolean) =
        settingsRepository.update { it.copy(bubbleZoom = enabled) }

    /** The face the app's own interface is set in. Part of appearance, not of reading. */
    suspend fun setUiFont(font: AppFont) = settingsRepository.update { it.copy(uiFont = font) }

    suspend fun resetToDefaults() = settingsRepository.update { ReaderSettings.Default }

    /**
     * Puts back everything that decides how a book is *read*, and nothing else.
     *
     * Deliberately narrower than [resetToDefaults], which is the app's own "reset everything" and
     * takes the language and the library's layout with it. Someone who has made a book unreadable —
     * a font size they cannot see past, a leading that has run lines together — wants *that* undone,
     * in the screen where they did it; resetting the app's language at the same time turns a small
     * fix into a scare.
     *
     * The theme is left alone for the same reason: it is offered in the reader's panel, but it is
     * the app's appearance setting, and flipping a dark-mode user to light is not what "reset the
     * reading settings" should mean.
     */
    suspend fun resetReaderDefaults() = settingsRepository.update { settings ->
        settings.copy(
            readerFont = ReaderSettings.Default.readerFont,
            fontScale = ReaderSettings.Default.fontScale,
            lineHeightScale = ReaderSettings.Default.lineHeightScale,
            pageFitMode = ReaderSettings.Default.pageFitMode,
            readingDirection = ReaderSettings.Default.readingDirection,
            keepScreenOn = ReaderSettings.Default.keepScreenOn,
            showProgressIndicator = ReaderSettings.Default.showProgressIndicator,
            layout = ReaderSettings.Default.layout,
            tapToTurnPages = ReaderSettings.Default.tapToTurnPages,
            // Both of these decide what happens when a page is turned, so they belong to reading
            // rather than to appearance — unlike `uiFont`, which the reader's panel does not offer.
            pageTurnEffect = ReaderSettings.Default.pageTurnEffect,
            bubbleZoom = ReaderSettings.Default.bubbleZoom,
        )
    }

    companion object {
        /** Beyond this the text no longer fits a phone screen in any useful way. */
        const val MIN_FONT_SCALE = 0.7f
        const val MAX_FONT_SCALE = 3.0f

        /** Arabic script needs noticeably more leading than Latin, hence the generous ceiling. */
        const val MIN_LINE_HEIGHT = 0.8f
        const val MAX_LINE_HEIGHT = 2.5f
    }
}

/** A one-shot read of the settings, for callers that cannot await a `Flow`. */
class GetSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(): ReaderSettings = settingsRepository.currentSettings()
}
