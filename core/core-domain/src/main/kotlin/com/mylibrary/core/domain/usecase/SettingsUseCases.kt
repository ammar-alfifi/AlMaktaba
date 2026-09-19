package com.mylibrary.core.domain.usecase

import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.domain.model.ProgressScope
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

    suspend fun setColorSource(source: ColorSource) =
        settingsRepository.update { it.copy(colorSource = source) }

    /** Marks the first-run colour setup as answered, so it is not shown again. */
    suspend fun setSetupComplete(complete: Boolean) =
        settingsRepository.update { it.copy(setupComplete = complete) }

    suspend fun setLanguage(language: AppLanguage) = settingsRepository.update { it.copy(language = language) }

    suspend fun setViewMode(mode: ViewMode) = settingsRepository.update { it.copy(viewMode = mode) }

    suspend fun setLibrarySort(sort: LibrarySort) = settingsRepository.update { it.copy(librarySort = sort) }

    suspend fun setReaderFont(font: ReaderFont) = settingsRepository.update { it.copy(readerFont = font) }

    suspend fun setFontScale(scale: Float) =
        settingsRepository.update { it.copy(fontScale = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)) }

    suspend fun setLineHeightScale(scale: Float) =
        settingsRepository.update { it.copy(lineHeightScale = scale.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)) }

    suspend fun setMarginScale(scale: Float) =
        settingsRepository.update { it.copy(marginScale = scale.coerceIn(MIN_MARGIN, MAX_MARGIN)) }

    suspend fun setParagraphSpacingScale(scale: Float) =
        settingsRepository.update {
            it.copy(paragraphSpacingScale = scale.coerceIn(MIN_PARAGRAPH_SPACING, MAX_PARAGRAPH_SPACING))
        }

    suspend fun setFirstLineIndent(enabled: Boolean) =
        settingsRepository.update { it.copy(firstLineIndent = enabled) }

    suspend fun setPageFitMode(mode: PageFitMode) = settingsRepository.update { it.copy(pageFitMode = mode) }

    suspend fun setReadingDirection(direction: ReadingDirection) =
        settingsRepository.update { it.copy(readingDirection = direction) }

    suspend fun setKeepScreenOn(enabled: Boolean) = settingsRepository.update { it.copy(keepScreenOn = enabled) }

    suspend fun setShowProgressIndicator(enabled: Boolean) =
        settingsRepository.update { it.copy(showProgressIndicator = enabled) }

    suspend fun setLayout(layout: ReaderLayout) = settingsRepository.update { it.copy(layout = layout) }

    suspend fun setProgressScope(scope: ProgressScope) =
        settingsRepository.update { it.copy(progressScope = scope) }

    suspend fun setTapToTurnPages(enabled: Boolean) =
        settingsRepository.update { it.copy(tapToTurnPages = enabled) }

    suspend fun setReverseTapZones(enabled: Boolean) =
        settingsRepository.update { it.copy(reverseTapZones = enabled) }

    suspend fun setPageTurnEffect(effect: PageTurnEffect) =
        settingsRepository.update { it.copy(pageTurnEffect = effect) }

    suspend fun setHapticsEnabled(enabled: Boolean) =
        settingsRepository.update { it.copy(hapticsEnabled = enabled) }

    suspend fun setBubbleZoom(enabled: Boolean) =
        settingsRepository.update { it.copy(bubbleZoom = enabled) }

    /**
     * The app's own settings back to their defaults — the interface, and nothing of the reader.
     *
     * This is what the settings screen offers, and that screen is the interface's own: it holds the
     * theme, the colour and the language. Everything that decides how a *book* is read is reset from
     * [resetReaderDefaults] instead, in the reader's own panel, which is the surface that offers it
     * and the only one where the result is visible on the page behind it.
     *
     * The answer to the first-run setup is kept deliberately. Resetting the app's appearance is
     * something a reader does *to* the app, and being sent through a welcome screen the next time
     * they open it would read as the reset having broken something.
     *
     * The library's view mode and sort are left alone for the same reason the reading settings are:
     * they are not on this screen, they are on the shelf, and a button labelled "reset interface
     * settings" has no business rearranging a library the reader is not looking at.
     */
    suspend fun resetToDefaults() = settingsRepository.update { it.resetInterfaceDefaults() }

    /**
     * Puts back everything that decides how a book is *read*, and nothing else.
     *
     * Deliberately the mirror of [resetToDefaults], and disjoint from it: that one is the interface's
     * and this is the book's, so between them every field has exactly one home and neither can undo
     * the other's work. Someone who has made a book unreadable — a font size they cannot see past, a
     * leading that has run lines together, margins that leave no column — wants *that* undone, in the
     * screen where they did it; resetting the app's language at the same time turns a small fix into
     * a scare.
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
            marginScale = ReaderSettings.Default.marginScale,
            paragraphSpacingScale = ReaderSettings.Default.paragraphSpacingScale,
            firstLineIndent = ReaderSettings.Default.firstLineIndent,
            pageFitMode = ReaderSettings.Default.pageFitMode,
            readingDirection = ReaderSettings.Default.readingDirection,
            keepScreenOn = ReaderSettings.Default.keepScreenOn,
            showProgressIndicator = ReaderSettings.Default.showProgressIndicator,
            layout = ReaderSettings.Default.layout,
            progressScope = ReaderSettings.Default.progressScope,
            tapToTurnPages = ReaderSettings.Default.tapToTurnPages,
            reverseTapZones = ReaderSettings.Default.reverseTapZones,
            // All three of these decide what happens when a page is turned, so they belong to
            // reading rather than to appearance — which is also why the reader's panel is where
            // they are offered.
            pageTurnEffect = ReaderSettings.Default.pageTurnEffect,
            hapticsEnabled = ReaderSettings.Default.hapticsEnabled,
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

        /**
         * The margins' own range, as a multiplier on the reader's base margin.
         *
         * The floor is not zero: a line that touches both edges of the display is unreadable rather
         * than merely tight, and a reader who dragged the slider to the end would have no way to tell
         * that from the app having broken. The ceiling is where the column gets so narrow on a phone
         * that a word stops fitting on a line.
         */
        const val MIN_MARGIN = 0.5f
        const val MAX_MARGIN = 2.5f

        /** Zero is allowed on purpose — see `ReaderSettings.paragraphSpacingScale`. */
        const val MIN_PARAGRAPH_SPACING = 0f
        const val MAX_PARAGRAPH_SPACING = 3f
    }
}

/** A one-shot read of the settings, for callers that cannot await a `Flow`. */
class GetSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(): ReaderSettings = settingsRepository.currentSettings()
}
