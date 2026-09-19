package com.mylibrary.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.usecase.ObserveSettingsUseCase
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The settings screen's ViewModel.
 *
 * Settings live in a single DataStore-backed object that the whole app observes, so this class does
 * not own a copy of them: it mirrors [ObserveSettingsUseCase] into state and turns intents into
 * [UpdateSettingsUseCase] calls. Nothing here decides what a setting *means* — that stays in the
 * domain model — which is what keeps this screen from becoming a second, divergent source of truth.
 *
 * There is no slider coalescing here, and its absence is deliberate: every control on this screen is
 * a discrete choice — a theme, a colour, a language — so an intent is one write and one write is
 * enough. The reading sliders, which do need it, are the reader's, and the reader's ViewModel is
 * where that machinery lives.
 *
 * A version string is injected rather than derived from `BuildConfig`: this is a library module, so
 * its own generated `BuildConfig.VERSION_NAME` would report the library's version (or not exist at
 * all — AGP only generates one for a library when asked) while `:app`'s build config is invisible to
 * a module it depends on. `PackageManager` reports the version of the package the user actually
 * installed, which is the only answer that is correct in every build variant.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    @ApplicationContext context: Context,
) : MviViewModel<SettingsUiState, SettingsIntent, SettingsEffect>(
    SettingsUiState(appVersionName = context.appVersionName()),
) {

    init {
        launch {
            observeSettings().collect { settings ->
                setState { copy(settings = settings) }
            }
        }
    }

    override fun onIntent(intent: SettingsIntent) {
        // The store echoes a change back within milliseconds, but a switch that waited for that round
        // trip would freeze mid-animation, so the intent is applied to the state immediately and
        // persistence follows. The optimistic value and the persisted value are computed from the
        // same intent, and `SettingsIntentMappingTest` asserts that for every intent, so the store's
        // own emission is a no-op re-render rather than a correction.
        setState { copy(settings = settings.updatedBy(intent)) }

        launch {
            updateSettings.persist(intent)
            if (intent == SettingsIntent.ResetToDefaults) {
                sendEffect(SettingsEffect.ShowMessage(R.string.settings_reset_done))
            }
        }
    }
}

/**
 * The settings this intent asks for, applied to [this].
 *
 * This is the screen's definition of what each intent means, kept pure so it can be unit-tested
 * without a store, a dispatcher or an Android device.
 */
internal fun ReaderSettings.updatedBy(intent: SettingsIntent): ReaderSettings = when (intent) {
    is SettingsIntent.ThemeModeChanged -> copy(themeMode = intent.mode)
    is SettingsIntent.ColorSourceChanged -> copy(colorSource = intent.source)
    SettingsIntent.SetupCompleted -> copy(setupComplete = true)
    is SettingsIntent.LanguageChanged -> copy(language = intent.language)
    SettingsIntent.ResetToDefaults -> resetInterfaceDefaults()
}

/**
 * Writes the change [intent] asks for.
 *
 * One setter per intent, never a whole-object write: the reader's own controls change fields of the
 * same object from another screen, and a "replace everything" call here would clobber whatever the
 * user changed there in between. That matters most for [SettingsIntent.ResetToDefaults], which used
 * to write a whole default object back and now names the three fields this screen is about — the
 * reading settings it does not own are simply not in the list.
 */
internal suspend fun UpdateSettingsUseCase.persist(intent: SettingsIntent): Unit = when (intent) {
    is SettingsIntent.ThemeModeChanged -> setThemeMode(intent.mode)
    is SettingsIntent.ColorSourceChanged -> setColorSource(intent.source)
    SettingsIntent.SetupCompleted -> setSetupComplete(true)
    is SettingsIntent.LanguageChanged -> setLanguage(intent.language)
    SettingsIntent.ResetToDefaults -> resetToDefaults()
}

/**
 * The installed package's `versionName`, or an empty string if it cannot be read.
 *
 * The API 33 split is not cosmetic: the deprecated `getPackageInfo(String, Int)` is what still
 * exists below 33, and calling it on a newer platform reads flags as an `Int` where the platform
 * now expects a `PackageInfoFlags` value. Failure is swallowed because a settings screen that
 * refuses to open over a missing version string would be a far worse bug than the missing string.
 */
private fun Context.appVersionName(): String = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    info.versionName.orEmpty()
}.getOrDefault("")
