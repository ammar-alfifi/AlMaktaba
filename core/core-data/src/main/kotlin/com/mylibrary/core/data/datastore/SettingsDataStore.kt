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
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderPaper
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ReaderLayout
import com.mylibrary.core.domain.model.TextAlignment
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
        this[Keys.COLOR_SOURCE] = settings.colorSource.name
        this[Keys.SETUP_COMPLETE] = settings.setupComplete
        this[Keys.LANGUAGE] = settings.language.name
        this[Keys.VIEW_MODE] = settings.viewMode.name
        this[Keys.LIBRARY_SORT] = settings.librarySort.name
        this[Keys.READER_FONT] = settings.readerFont.name
        this[Keys.FONT_SCALE] = settings.fontScale
        this[Keys.LINE_HEIGHT_SCALE] = settings.lineHeightScale
        this[Keys.MARGIN_SCALE] = settings.marginScale
        this[Keys.PARAGRAPH_SPACING_SCALE] = settings.paragraphSpacingScale
        this[Keys.FIRST_LINE_INDENT] = settings.firstLineIndent
        this[Keys.TEXT_ALIGN] = settings.textAlign.name
        this[Keys.PAGE_FIT_MODE] = settings.pageFitMode.name
        this[Keys.READING_DIRECTION] = settings.readingDirection.name
        this[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
        this[Keys.SHOW_PROGRESS_INDICATOR] = settings.showProgressIndicator
        this[Keys.READER_PAPER] = settings.readerPaper.name
        this[Keys.LAYOUT] = settings.layout.name
        this[Keys.PROGRESS_SCOPE] = settings.progressScope.name
        this[Keys.TAP_TO_TURN_PAGES] = settings.tapToTurnPages
        this[Keys.REVERSE_TAP_ZONES] = settings.reverseTapZones
        this[Keys.PAGE_TURN_EFFECT] = settings.pageTurnEffect.name
        this[Keys.HAPTICS_ENABLED] = settings.hapticsEnabled
        this[Keys.BUBBLE_ZOOM] = settings.bubbleZoom
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
    val COLOR_SOURCE = stringPreferencesKey("color_source")
    val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")

    // Written by every build up to 1.4.0 and read by no build after it, except as the fallback for
    // `color_source` below. Kept rather than deleted because that fallback is the whole reason an
    // upgrade does not silently repaint: a reader who had turned dynamic colour *off* must come back
    // to the teal scheme they chose, not to the wallpaper one they refused.
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val LANGUAGE = stringPreferencesKey("language")

    // The interface font's own key, `ui_font`, is deliberately absent: builds up to 1.6.0 wrote it
    // and no build reads it now that the setting is gone. Nothing rewrites or deletes it — a
    // preferences file is not a schema to be migrated, and a key nothing looks at costs nothing —
    // but it must not be *read* either, or the app would go on honouring a choice it no longer
    // offers.
    val VIEW_MODE = stringPreferencesKey("view_mode")
    val LIBRARY_SORT = stringPreferencesKey("library_sort")
    val READER_FONT = stringPreferencesKey("reader_font")
    val FONT_SCALE = floatPreferencesKey("font_scale")
    val LINE_HEIGHT_SCALE = floatPreferencesKey("line_height_scale")
    val MARGIN_SCALE = floatPreferencesKey("margin_scale")
    val PARAGRAPH_SPACING_SCALE = floatPreferencesKey("paragraph_spacing_scale")
    val FIRST_LINE_INDENT = booleanPreferencesKey("first_line_indent")
    val TEXT_ALIGN = stringPreferencesKey("text_align")
    val PAGE_FIT_MODE = stringPreferencesKey("page_fit_mode")
    val READING_DIRECTION = stringPreferencesKey("reading_direction")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val SHOW_PROGRESS_INDICATOR = booleanPreferencesKey("show_progress_indicator")
    val READER_PAPER = stringPreferencesKey("reader_paper")
    // Named "reflow_mode" when only text could scroll. The layout setting inherited this key
    // rather than taking a new one, so a value written by an older build is still read: there is
    // no migration mechanism here, and orphaning the key would silently reset every reader.
    val LAYOUT = stringPreferencesKey("reflow_mode")
    val PROGRESS_SCOPE = stringPreferencesKey("progress_scope")
    val TAP_TO_TURN_PAGES = booleanPreferencesKey("tap_to_turn_pages")
    val REVERSE_TAP_ZONES = booleanPreferencesKey("reverse_tap_zones")
    val PAGE_TURN_EFFECT = stringPreferencesKey("page_turn_effect")
    val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    val BUBBLE_ZOOM = booleanPreferencesKey("bubble_zoom")
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
        colorSource = preferences[Keys.COLOR_SOURCE].toEnumOrNull<ColorSource>()
            ?: when (preferences[Keys.DYNAMIC_COLOR]) {
                // The key this setting replaced. A reader who had dynamic colour switched *on* gets
                // the wallpaper they were already looking at; one who had it off gets the teal
                // scheme they chose over it. Either way nobody's app repaints itself on upgrade.
                true -> ColorSource.WALLPAPER
                false -> ColorSource.TEAL
                // Neither key. That is a fresh install rather than an upgrade — an existing install
                // always has the old switch, because it was written on every save — so there is no
                // previous choice to honour and this is the domain's own default. The setup screen
                // a first launch opens on then offers the wallpaper as one of the choices, which is
                // where a reader who wants it can say so.
                null -> defaults.colorSource
            },
        // Absent for anyone who upgrades, and an empty file is the only thing that means "first
        // run" — which is exactly what a fresh install has, and what the IO-failure fallback emits.
        setupComplete = preferences[Keys.SETUP_COMPLETE] ?: preferences.asMap().isNotEmpty(),
        language = preferences[Keys.LANGUAGE].toEnum(defaults.language),
        viewMode = preferences[Keys.VIEW_MODE].toEnum(defaults.viewMode),
        librarySort = preferences[Keys.LIBRARY_SORT].toEnum(defaults.librarySort),
        readerFont = preferences[Keys.READER_FONT].toEnum(defaults.readerFont),
        fontScale = preferences[Keys.FONT_SCALE] ?: defaults.fontScale,
        lineHeightScale = preferences[Keys.LINE_HEIGHT_SCALE] ?: defaults.lineHeightScale,
        marginScale = preferences[Keys.MARGIN_SCALE] ?: defaults.marginScale,
        paragraphSpacingScale = preferences[Keys.PARAGRAPH_SPACING_SCALE]
            ?: defaults.paragraphSpacingScale,
        firstLineIndent = preferences[Keys.FIRST_LINE_INDENT] ?: defaults.firstLineIndent,
        textAlign = preferences[Keys.TEXT_ALIGN].toEnum(defaults.textAlign),
        pageFitMode = preferences[Keys.PAGE_FIT_MODE].toEnum(defaults.pageFitMode),
        readingDirection = preferences[Keys.READING_DIRECTION].toEnum(defaults.readingDirection),
        keepScreenOn = preferences[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
        showProgressIndicator = preferences[Keys.SHOW_PROGRESS_INDICATOR]
            ?: defaults.showProgressIndicator,
        readerPaper = preferences[Keys.READER_PAPER].toEnum(defaults.readerPaper),
        layout = preferences[Keys.LAYOUT].toEnum(defaults.layout),
        progressScope = preferences[Keys.PROGRESS_SCOPE].toEnum(defaults.progressScope),
        tapToTurnPages = preferences[Keys.TAP_TO_TURN_PAGES] ?: defaults.tapToTurnPages,
        reverseTapZones = preferences[Keys.REVERSE_TAP_ZONES] ?: defaults.reverseTapZones,
        pageTurnEffect = preferences[Keys.PAGE_TURN_EFFECT].toEnum(defaults.pageTurnEffect),
        hapticsEnabled = preferences[Keys.HAPTICS_ENABLED] ?: defaults.hapticsEnabled,
        bubbleZoom = preferences[Keys.BUBBLE_ZOOM] ?: defaults.bubbleZoom,
    )
}

/** Resolves a stored enum name, falling back to [fallback] for absent or unrecognised values. */
private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.toEnumOrNull<T>() ?: fallback

/**
 * Resolves a stored enum name, or `null` when it is absent or unrecognised.
 *
 * The nullable half exists for [ColorSource], whose *absence* means something in particular — it is
 * a value written by an older build under a different key — and so has to be distinguishable from an
 * unrecognised one.
 */
private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
