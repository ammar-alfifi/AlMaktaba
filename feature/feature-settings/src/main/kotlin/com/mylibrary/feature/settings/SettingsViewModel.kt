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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import javax.inject.Inject

/**
 * The settings screen's ViewModel.
 *
 * Settings live in a single DataStore-backed object that the whole app observes, so this class does
 * not own a copy of them: it mirrors [ObserveSettingsUseCase] into state and turns intents into
 * [UpdateSettingsUseCase] calls. Nothing here decides what a setting *means* — that stays in the
 * domain model — which is what keeps this screen from becoming a second, divergent source of truth.
 *
 * A version string is injected rather than derived from `BuildConfig`: this is a library module, so
 * its own generated `BuildConfig.VERSION_NAME` would report the library's version (or not exist at
 * all — AGP only generates one for a library when asked) while `:app`'s build config is invisible to
 * a module it depends on. `PackageManager` reports the version of the package the user actually
 * installed, which is the only answer that is correct in every build variant.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    @ApplicationContext context: Context,
) : MviViewModel<SettingsUiState, SettingsIntent, SettingsEffect>(
    SettingsUiState(appVersionName = context.appVersionName()),
) {

    /**
     * Slider drags, held back from the store until the finger stops.
     *
     * A drag produces roughly one intent per frame, and every write to DataStore is a
     * read-modify-write on disk plus a new emission to *every* observer in the app — the theme, the
     * language and the library are all rebuilt on each one. Writing per frame turns a two-second
     * drag into a hundred-odd transactions and a hundred recompositions of the whole app, so the
     * drag is funnelled through here and [debounce]d to one write per quiet period instead.
     *
     * Debounce rather than throttle because the value that matters is the one the user *releases*
     * on: debouncing keeps the last value of the gesture, while a leading-edge throttle would
     * persist the first and need a flush to catch up.
     *
     * The trade-off is that the final value of a drag is only written once the gesture has been
     * still for [SLIDER_DEBOUNCE_MILLIS], so navigating away inside that window drops the last
     * increment. That is a hundred milliseconds of exposure on a control the user is still holding,
     * which is a far better bargain than a write per frame. The buffer is generous because a
     * `SharedFlow` drops values that arrive when its buffer is full, and a dropped frame here would
     * be a silently ignored slider movement rather than a stale one.
     */
    private val sliderChanges = MutableSharedFlow<SettingsIntent>(extraBufferCapacity = SLIDER_BUFFER)

    init {
        launch {
            observeSettings().collect { settings ->
                setState { copy(settings = settings) }
            }
        }
        launch {
            sliderChanges
                .debounce(SLIDER_DEBOUNCE_MILLIS)
                .collect { intent -> updateSettings.persist(intent) }
        }
    }

    override fun onIntent(intent: SettingsIntent) {
        // The store echoes a change back within milliseconds, but a slider that waited for that
        // round trip would visibly trail the finger and a switch would freeze mid-animation, so the
        // intent is applied to the state immediately and persistence follows. The optimistic value
        // and the persisted value are computed from the same intent, and `SettingsIntentMappingTest`
        // asserts that for every intent, so the store's own emission is a no-op re-render rather
        // than a correction.
        setState { copy(settings = settings.updatedBy(intent)) }

        if (intent.isSliderDrag) {
            // Emitted from a coroutine so a slider frame is never dropped for want of buffer space.
            launch { sliderChanges.emit(intent) }
        } else {
            launch {
                updateSettings.persist(intent)
                if (intent == SettingsIntent.ResetToDefaults) {
                    sendEffect(SettingsEffect.ShowMessage(R.string.settings_reset_done))
                }
            }
        }
    }

    private companion object {
        /**
         * Long enough to collapse a drag into a handful of writes, short enough that the value is
         * already stored by the time the user's finger has settled on the next control.
         */
        const val SLIDER_DEBOUNCE_MILLIS = 120L

        /** Two seconds of dragging at 60 fps; see [sliderChanges]. */
        const val SLIDER_BUFFER = 128
    }
}

/**
 * True for the intents a continuous gesture produces, and only those.
 *
 * Exposed as a property rather than as a branch inside `onIntent` so that the coalescing path is
 * decided in one named place: a new intent added to the sealed interface defaults to the
 * write-immediately path, and `SettingsIntentMappingTest` pins down which two are continuous.
 */
internal val SettingsIntent.isSliderDrag: Boolean
    get() = this is SettingsIntent.FontScaleChanged || this is SettingsIntent.LineHeightChanged

/**
 * The settings this intent asks for, applied to [this].
 *
 * This is the screen's definition of what each intent means, kept pure so it can be unit-tested
 * without a store, a dispatcher or an Android device. The two sliders clamp with the *domain's*
 * bounds rather than copies of them, so the optimistic value shown during a drag is exactly the
 * value that can be stored — if this clamped differently from [UpdateSettingsUseCase], the slider
 * would visibly jump back the moment the store echoed.
 */
internal fun ReaderSettings.updatedBy(intent: SettingsIntent): ReaderSettings = when (intent) {
    is SettingsIntent.ThemeModeChanged -> copy(themeMode = intent.mode)
    is SettingsIntent.DynamicColorToggled -> copy(dynamicColor = intent.enabled)
    is SettingsIntent.LanguageChanged -> copy(language = intent.language)
    is SettingsIntent.ViewModeChanged -> copy(viewMode = intent.mode)
    is SettingsIntent.SortChanged -> copy(librarySort = intent.sort)
    is SettingsIntent.ReaderFontChanged -> copy(readerFont = intent.font)
    is SettingsIntent.FontScaleChanged -> copy(
        fontScale = intent.scale.coerceIn(
            UpdateSettingsUseCase.MIN_FONT_SCALE,
            UpdateSettingsUseCase.MAX_FONT_SCALE,
        ),
    )
    is SettingsIntent.LineHeightChanged -> copy(
        lineHeightScale = intent.scale.coerceIn(
            UpdateSettingsUseCase.MIN_LINE_HEIGHT,
            UpdateSettingsUseCase.MAX_LINE_HEIGHT,
        ),
    )
    is SettingsIntent.PageFitChanged -> copy(pageFitMode = intent.mode)
    is SettingsIntent.ReadingDirectionChanged -> copy(readingDirection = intent.direction)
    is SettingsIntent.KeepScreenOnToggled -> copy(keepScreenOn = intent.enabled)
    is SettingsIntent.ShowProgressToggled -> copy(showProgressIndicator = intent.enabled)
    is SettingsIntent.PageSnappingToggled -> copy(pageSnapping = intent.enabled)
    is SettingsIntent.ReflowModeChanged -> copy(reflowMode = intent.mode)
    is SettingsIntent.TapToTurnToggled -> copy(tapToTurnPages = intent.enabled)
    SettingsIntent.ResetToDefaults -> ReaderSettings.Default
}

/**
 * Writes the change [intent] asks for.
 *
 * One setter per intent, never a whole-object write: the reader's own font-size control changes the
 * same field from another screen, and a "replace everything" call here would clobber whatever the
 * user changed there in between.
 */
internal suspend fun UpdateSettingsUseCase.persist(intent: SettingsIntent): Unit = when (intent) {
    is SettingsIntent.ThemeModeChanged -> setThemeMode(intent.mode)
    is SettingsIntent.DynamicColorToggled -> setDynamicColor(intent.enabled)
    is SettingsIntent.LanguageChanged -> setLanguage(intent.language)
    is SettingsIntent.ViewModeChanged -> setViewMode(intent.mode)
    is SettingsIntent.SortChanged -> setLibrarySort(intent.sort)
    is SettingsIntent.ReaderFontChanged -> setReaderFont(intent.font)
    is SettingsIntent.FontScaleChanged -> setFontScale(intent.scale)
    is SettingsIntent.LineHeightChanged -> setLineHeightScale(intent.scale)
    is SettingsIntent.PageFitChanged -> setPageFitMode(intent.mode)
    is SettingsIntent.ReadingDirectionChanged -> setReadingDirection(intent.direction)
    is SettingsIntent.KeepScreenOnToggled -> setKeepScreenOn(intent.enabled)
    is SettingsIntent.ShowProgressToggled -> setShowProgressIndicator(intent.enabled)
    is SettingsIntent.PageSnappingToggled -> setPageSnapping(intent.enabled)
    is SettingsIntent.ReflowModeChanged -> setReflowMode(intent.mode)
    is SettingsIntent.TapToTurnToggled -> setTapToTurnPages(intent.enabled)
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
