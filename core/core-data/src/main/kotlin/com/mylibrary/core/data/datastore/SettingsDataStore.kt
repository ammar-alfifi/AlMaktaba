package com.mylibrary.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ReflowMode
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings store, backed by Preferences DataStore.
 *
 * Every read goes through [ReaderSettings.fromPreferences], which resolves unknown or absent values
 * to the defaults rather than failing. That matters for two reasons: a fresh install has no
 * preferences at all, and a value written by an older version — a theme mode that has since been
 * renamed — must not brick the app on upgrade.
 *
 * The `catch` on [settings] handles the one failure DataStore can genuinely suffer: a corrupted
 * preferences file throws `IOException` on every read, which would otherwise crash at launch with no
 * way for the user to recover. Falling back to defaults turns that into "settings were reset",
 * which is survivable. Cancellation is deliberately *not* caught.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<ReaderSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> ReaderSettings.fromPreferences(preferences) }

    /**
     * Applies [transform] to the current settings and persists the result.
     *
     * Reading and writing inside DataStore's own `edit` transaction means two concurrent settings
     * changes are applied in sequence rather than one silently overwriting the other — which is
     * exactly what happens if a caller reads a stale snapshot and writes back a whole object.
     */
    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { preferences ->
            val updated = transform(ReaderSettings.fromPreferences(preferences))
            preferences.write(updated)
        }
    }

    private fun MutablePreferences.write(settings: ReaderSettings) {
        this[Keys.THEME_MODE] = settings.themeMode.name
        this[Keys.DYNAMIC_COLOR] = settings.dynamicColor
        this[Keys.LANGUAGE] = settings.language.name
        this[Keys.VIEW_MODE] = settings.viewMode.name
        this[Keys.LIBRARY_SORT] = settings.librarySort.name
        this[Keys.READER_FONT] = settings.readerFont.name
        this[Keys.FONT_SCALE] = settings.fontScale
        this[Keys.LINE_HEIGHT_SCALE] = settings.lineHeightScale
        this[Keys.PAGE_FIT_MODE] = settings.pageFitMode.name
        this[Keys.READING_DIRECTION] = settings.readingDirection.name
        this[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
        this[Keys.SHOW_PROGRESS_INDICATOR] = settings.showProgressIndicator
        this[Keys.PAGE_SNAPPING] = settings.pageSnapping
        this[Keys.REFLOW_MODE] = settings.reflowMode.name
        this[Keys.TAP_TO_TURN_PAGES] = settings.tapToTurnPages
    }

}

/**
 * Preference keys.
 *
 * File-private rather than nested in the class so the file-level mapper can read the same keys the
 * writer uses; a second copy of these strings is the classic way a settings screen ends up writing
 * values nothing ever reads.
 */
private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val LANGUAGE = stringPreferencesKey("language")
    val VIEW_MODE = stringPreferencesKey("view_mode")
    val LIBRARY_SORT = stringPreferencesKey("library_sort")
    val READER_FONT = stringPreferencesKey("reader_font")
    val FONT_SCALE = floatPreferencesKey("font_scale")
    val LINE_HEIGHT_SCALE = floatPreferencesKey("line_height_scale")
    val PAGE_FIT_MODE = stringPreferencesKey("page_fit_mode")
    val READING_DIRECTION = stringPreferencesKey("reading_direction")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val SHOW_PROGRESS_INDICATOR = booleanPreferencesKey("show_progress_indicator")
    val PAGE_SNAPPING = booleanPreferencesKey("page_snapping")
    val REFLOW_MODE = stringPreferencesKey("reflow_mode")
    val TAP_TO_TURN_PAGES = booleanPreferencesKey("tap_to_turn_pages")
}

/**
 * Rebuilds [ReaderSettings] from stored preferences.
 *
 * Each enum is resolved by name with a fallback to the current default, so renaming an enum entry
 * degrades to the default instead of throwing `IllegalArgumentException` at launch.
 */
private fun ReaderSettings.Companion.fromPreferences(preferences: Preferences): ReaderSettings {
    val defaults = ReaderSettings.Default
    return ReaderSettings(
        themeMode = preferences[Keys.THEME_MODE].toEnum(defaults.themeMode),
        dynamicColor = preferences[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
        language = preferences[Keys.LANGUAGE].toEnum(defaults.language),
        viewMode = preferences[Keys.VIEW_MODE].toEnum(defaults.viewMode),
        librarySort = preferences[Keys.LIBRARY_SORT].toEnum(defaults.librarySort),
        readerFont = preferences[Keys.READER_FONT].toEnum(defaults.readerFont),
        fontScale = preferences[Keys.FONT_SCALE] ?: defaults.fontScale,
        lineHeightScale = preferences[Keys.LINE_HEIGHT_SCALE] ?: defaults.lineHeightScale,
        pageFitMode = preferences[Keys.PAGE_FIT_MODE].toEnum(defaults.pageFitMode),
        readingDirection = preferences[Keys.READING_DIRECTION].toEnum(defaults.readingDirection),
        keepScreenOn = preferences[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
        showProgressIndicator = preferences[Keys.SHOW_PROGRESS_INDICATOR]
            ?: defaults.showProgressIndicator,
        pageSnapping = preferences[Keys.PAGE_SNAPPING] ?: defaults.pageSnapping,
        reflowMode = preferences[Keys.REFLOW_MODE].toEnum(defaults.reflowMode),
        tapToTurnPages = preferences[Keys.TAP_TO_TURN_PAGES] ?: defaults.tapToTurnPages,
    )
}

/** Resolves a stored enum name, falling back to [fallback] for absent or unrecognised values. */
private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback
