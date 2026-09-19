package com.mylibrary.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ThemeMode

/**
 * Everything the settings screen draws.
 *
 * The whole [ReaderSettings] object is carried, rather than the four individual fields the screen
 * happens to render today. Two reasons: the screen is the one place a user sees *all* of their
 * preferences, and a state that mirrored only some of them would silently show a stale value the
 * moment a new setting is added to the domain model — the compiler cannot warn about a field that
 * was never copied into the screen's own state.
 *
 * Marked [Immutable] because [ReaderSettings] is a data class of enums and primitives: it is a
 * promise the underlying types already keep, and it lets Compose skip recomposing the sections
 * whose settings did not change.
 */
@Immutable
data class SettingsUiState(
    val settings: ReaderSettings = ReaderSettings.Default,
    /**
     * The installed app's version, resolved once by the ViewModel.
     *
     * Empty when the package manager could not answer (a rare but real failure); the screen then
     * shows a localized "unknown" label instead of an empty row, because a settings screen with a
     * blank line where the version belongs looks like a bug.
     */
    val appVersionName: String = "",
)

/**
 * Every action the settings screen can report.
 *
 * Each entry carries the *chosen value* rather than a delta, so the ViewModel never has to read
 * the current state to understand an intent, and the same intent replayed twice is idempotent.
 *
 * Four intents, because the screen holds four decisions: the theme, the colour, the language, and
 * "put these back". Everything about how a book is read is an intent of the reader's own — those
 * settings live in the reader's panel, and one sealed interface per surface is what keeps it
 * visible that they do.
 */
sealed interface SettingsIntent {

    data class ThemeModeChanged(val mode: ThemeMode) : SettingsIntent

    data class ColorSourceChanged(val source: ColorSource) : SettingsIntent

    /** The first-run colour setup has been answered; it is not shown again. */
    data object SetupCompleted : SettingsIntent

    data class LanguageChanged(val language: AppLanguage) : SettingsIntent

    /** The interface's own settings back to their defaults; reading settings are untouched. */
    data object ResetToDefaults : SettingsIntent
}

/**
 * One-shot events the screen consumes exactly once.
 *
 * These are effects rather than state on purpose: a "settings were reset" confirmation modelled as
 * a state field would re-show itself on every recomposition and every rotation, which is the
 * classic snackbar bug. The message travels as a resource id so that the *decision* of what
 * happened stays in the ViewModel while the *wording and locale* stay in the resources.
 */
sealed interface SettingsEffect {

    data class ShowMessage(@param:StringRes val messageResId: Int) : SettingsEffect
}
