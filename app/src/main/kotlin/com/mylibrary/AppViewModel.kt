package com.mylibrary

import androidx.compose.runtime.Immutable
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.usecase.ObserveSettingsUseCase
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch

/** App-wide state: the settings that decide the theme and the language. */
@Immutable
data class AppUiState(
    val settings: ReaderSettings = ReaderSettings.Default,
    /**
     * True once the persisted settings have been read.
     *
     * The UI waits for this before its first frame because painting with the default theme and then
     * switching to the user's dark theme is a visible flash on every cold start — and with a dark
     * theme the flash is a white screen, which is the worst version of it.
     */
    val isReady: Boolean = false,
)

/** Holds the settings every screen is themed and translated by. */
@HiltViewModel
class AppViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
) : MviViewModel<AppUiState, Nothing, Nothing>(AppUiState()) {

    init {
        launch {
            observeSettings()
                // If settings cannot be read the app still has to start; defaults are a far better
                // outcome than a blank window.
                .catch { setState { copy(isReady = true) } }
                .collect { settings -> setState { copy(settings = settings, isReady = true) } }
        }
    }

    override fun onIntent(intent: Nothing) = Unit
}
