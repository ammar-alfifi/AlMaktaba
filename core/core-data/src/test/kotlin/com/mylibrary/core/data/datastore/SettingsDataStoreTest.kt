package com.mylibrary.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mylibrary.core.domain.model.ColorSource
import com.mylibrary.core.domain.model.ReaderPaper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The settings store's two pieces of history.
 *
 * Both are about what happens to a reader who has already been using the app, which is the case no
 * unit test of the *model* can reach: the model only ever sees a [Preferences] object that has
 * already been resolved, and by then the interesting question — "was anything here at all?" — has
 * been thrown away.
 */
class SettingsDataStoreTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "settings-test-${hashCode()}")
    private var dataStore: DataStore<Preferences>? = null

    /**
     * A store over a real file, on the test's own scheduler.
     *
     * The scope is [TestScope.backgroundScope] because DataStore keeps a coroutine alive for as long
     * as it exists: on the test's *foreground* scope, `runTest` would wait for that coroutine
     * forever and every test would end in a timeout. Background scope is cancelled when the test
     * body finishes, which is exactly the lifetime wanted here.
     */
    private fun TestScope.store(): SettingsDataStore {
        val file = File(directory, "settings.preferences_pb").also { it.parentFile?.mkdirs() }
        val created = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        dataStore = created
        return SettingsDataStore(created)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    // region the first-run flag

    /**
     * A fresh install has an empty file, which is the only thing that means "first run".
     *
     * This is what makes `MyLibraryApp` show the colour setup on a new phone and never on an
     * existing one, and the two cases have to be told apart by the file's *contents* rather than by
     * a version number, because there is no version number and no migration mechanism here.
     */
    @Test
    fun `an empty store has not been set up`() = runTest {
        assertFalse(store().settings.first().setupComplete)
    }

    @Test
    fun `a store with anything at all in it counts as set up`() = runTest {
        val dataStore = store()
        // Exactly the shape an install from the previous release has: its own keys, and no flag.
        // Written raw, because writing through the store would *set* the flag and prove nothing.
        dataStore.rawEdit { it[stringPreferencesKey("theme_mode")] = "DARK" }

        assertTrue(
            "an existing install must not be sent through a welcome it has already had",
            dataStore.settings.first().setupComplete,
        )
    }

    @Test
    fun `an answered setup is remembered as answered`() = runTest {
        val dataStore = store()
        dataStore.update { it.copy(setupComplete = true) }

        assertTrue(dataStore.settings.first().setupComplete)
    }

    // endregion

    // region the colour source

    /**
     * The old switch is what an upgrade carries, and it has to be read as the choice it was.
     *
     * `dynamic_color` was a boolean, and a reader who had turned it *off* had chosen the app's own
     * palette over their wallpaper. Reading the absence of the new key as "follow the wallpaper"
     * would repaint their app on upgrade — turning a preference they had expressed into one they
     * had explicitly declined.
     */
    @Test
    fun `an install that had turned dynamic colour off upgrades to the app's own colour`() = runTest {
        val dataStore = store()
        dataStore.rawEdit { it[booleanPreferencesKey("dynamic_color")] = false }

        assertEquals(ColorSource.TEAL, dataStore.settings.first().colorSource)
    }

    @Test
    fun `an install that had left dynamic colour on upgrades to the wallpaper`() = runTest {
        val dataStore = store()
        dataStore.rawEdit { it[booleanPreferencesKey("dynamic_color")] = true }

        assertEquals(ColorSource.WALLPAPER, dataStore.settings.first().colorSource)
    }

    /**
     * A fresh install has neither key, and that is *not* the same case: there is no previous choice
     * to honour, so the domain's default applies and the wallpaper is offered on the setup screen
     * a first launch opens on.
     */
    @Test
    fun `a fresh install gets the default rather than the wallpaper`() = runTest {
        assertEquals(ColorSource.TEAL, store().settings.first().colorSource)
    }

    @Test
    fun `a chosen colour survives a read`() = runTest {
        val dataStore = store()
        dataStore.update { it.copy(colorSource = ColorSource.ROSE) }

        assertEquals(ColorSource.ROSE, dataStore.settings.first().colorSource)
    }

    /** A value from a build that spelled it differently degrades to the default, as every enum here does. */
    @Test
    fun `an unrecognised colour name falls back rather than throwing`() = runTest {
        val dataStore = store()
        dataStore.rawEdit { it[stringPreferencesKey("color_source")] = "CHARTREUSE" }

        assertEquals(ColorSource.TEAL, dataStore.settings.first().colorSource)
    }

    // endregion

    // region the page paper

    /** The reading paper is a reading setting, and persists like the rest. */
    @Test
    fun `a chosen page paper survives a read`() = runTest {
        val dataStore = store()
        dataStore.update { it.copy(readerPaper = ReaderPaper.BLACK) }

        assertEquals(ReaderPaper.BLACK, dataStore.settings.first().readerPaper)
    }

    /** An unknown paper name degrades to the default rather than throwing, as every enum here does. */
    @Test
    fun `an unrecognised page paper falls back rather than throwing`() = runTest {
        val dataStore = store()
        dataStore.rawEdit { it[stringPreferencesKey("reader_paper")] = "PARCHMENT" }

        assertEquals(ReaderPaper.DEFAULT, dataStore.settings.first().readerPaper)
    }

    // endregion

    /** Writes preferences directly, for the states only an older build could have produced. */
    private suspend fun SettingsDataStore.rawEdit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val backing = dataStore ?: throw IOException("no store")
        backing.edit { block(it) }
    }
}
