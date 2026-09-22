package com.mylibrary.feature.settings

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
import com.mylibrary.core.domain.model.TextAlignment
import com.mylibrary.core.domain.model.ThemeMode
import com.mylibrary.core.domain.model.ViewMode
import com.mylibrary.core.domain.repository.SettingsRepository
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down what every [SettingsIntent] means.
 *
 * The ViewModel computes each change twice — once optimistically, into the state the UI draws, and
 * once through [UpdateSettingsUseCase], into the store — and the two must agree or the value a user
 * picks is not the value they get back. Rather than testing each path separately, every case here
 * asserts all three: the expected settings, the optimistic transform and the persisted result. A
 * mapping edited on one side only fails on the other.
 *
 * The table is short because this screen is: it holds the theme, the colour, the language and
 * "put those back". Every control that decides how a *book* is read belongs to the reader's own
 * panel, and its intents are the reader's — a reading intent appearing here would mean the
 * separation the screen exists to keep had been lost.
 *
 * No mocks: a `SettingsRepository` is a two-method interface, and a real [UpdateSettingsUseCase]
 * over a fake store exercises the actual clamping and `copy` semantics that the screen relies on.
 */
class SettingsIntentMappingTest {

    private val repository = FakeSettingsRepository()
    private val updateSettings = UpdateSettingsUseCase(repository)

    @Test
    fun `each intent applies the expected change, optimistically and in the store`() {
        CASES.forEach { case ->
            assertEquals(
                "${case.name}: optimistic state",
                case.expected,
                case.startFrom.updatedBy(case.intent),
            )
            assertEquals(
                "${case.name}: persisted state",
                case.expected,
                persist(case.startFrom, case.intent),
            )
        }
    }

    @Test
    fun `no case is a no-op`() {
        // Guards the table itself: an "expected" copy-pasted from the start state would make the
        // test above pass without proving that the intent does anything at all.
        val indistinguishable = CASES.filter { it.expected == it.startFrom }.map { it.name }

        assertEquals("these cases assert nothing", emptyList<String>(), indistinguishable)
    }

    @Test
    fun `reset is the interface's alone, even from a state that changed everything`() {
        // The whole point of the narrowed reset, asserted through the screen's own path: a reader
        // who has spent time on the text of a book must not lose it to a button on a screen that
        // does not show a single one of those controls.
        val expected = everythingChanged.copy(
            themeMode = ReaderSettings.Default.themeMode,
            colorSource = ReaderSettings.Default.colorSource,
            language = ReaderSettings.Default.language,
        )

        assertEquals(expected, everythingChanged.updatedBy(SettingsIntent.ResetToDefaults))
        assertEquals(expected, persist(everythingChanged, SettingsIntent.ResetToDefaults))
    }

    @Test
    fun `a persisted intent changes only the field it names`() {
        // The reader's own panel changes some of the same fields from another screen; a mapping
        // that quietly reset its neighbours would fight it.
        val before = ReaderSettings.Default.copy(language = AppLanguage.ENGLISH, fontScale = 1.8f)
        val after = persist(before, SettingsIntent.ThemeModeChanged(ThemeMode.DARK))

        assertTrue("language must survive a theme change", after.language == AppLanguage.ENGLISH)
        assertEquals("and so must the reading settings", 1.8f, after.fontScale, 0.0001f)
        assertEquals(ThemeMode.DARK, after.themeMode)
    }

    /** Seeds the fake store, applies [intent], and reports what a reader of the store would see. */
    private fun persist(startFrom: ReaderSettings, intent: SettingsIntent): ReaderSettings =
        runBlocking {
            repository.set(startFrom)
            updateSettings.persist(intent)
            repository.currentSettings()
        }

    private data class Case(
        val name: String,
        val startFrom: ReaderSettings,
        val intent: SettingsIntent,
        val expected: ReaderSettings,
    )

    private companion object {
        private val Defaults = ReaderSettings.Default

        /** One entry per intent, each starting from a state that differs from what it produces. */
        private val CASES = listOf(
            Case(
                name = "theme mode",
                startFrom = Defaults,
                intent = SettingsIntent.ThemeModeChanged(ThemeMode.DARK),
                expected = Defaults.copy(themeMode = ThemeMode.DARK),
            ),
            Case(
                name = "colour source",
                startFrom = Defaults,
                intent = SettingsIntent.ColorSourceChanged(ColorSource.BLUE),
                expected = Defaults.copy(colorSource = ColorSource.BLUE),
            ),
            Case(
                name = "finishing the colour setup",
                startFrom = Defaults,
                intent = SettingsIntent.SetupCompleted,
                expected = Defaults.copy(setupComplete = true),
            ),
            Case(
                name = "language",
                startFrom = Defaults,
                intent = SettingsIntent.LanguageChanged(AppLanguage.ENGLISH),
                expected = Defaults.copy(language = AppLanguage.ENGLISH),
            ),
            Case(
                name = "reset",
                // Started from a changed state so that "reset" cannot pass by doing nothing.
                startFrom = Defaults.copy(themeMode = ThemeMode.DARK, fontScale = 2f),
                intent = SettingsIntent.ResetToDefaults,
                expected = Defaults.copy(fontScale = 2f),
            ),
        )
    }
}

/**
 * Every field at a value that is *not* its default, so that any reset has to do work.
 *
 * Shared by the two reset tests, and deliberately one object rather than one per test: the two are
 * duals — each asserts the half of the settings the other leaves alone — and a field added to
 * [ReaderSettings] is covered by both the moment it is given a non-default value here.
 */
private val everythingChanged = ReaderSettings(
    themeMode = ThemeMode.DARK,
    colorSource = ColorSource.ROSE,
    setupComplete = true,
    language = AppLanguage.ENGLISH,
    viewMode = ViewMode.LIST,
    librarySort = LibrarySort.TITLE_DESC,
    readerFont = ReaderFont.SERIF,
    fontScale = 2.4f,
    lineHeightScale = 2.1f,
    marginScale = 2.0f,
    paragraphSpacingScale = 2.5f,
    firstLineIndent = true,
    textAlign = TextAlignment.CENTER,
    pageFitMode = PageFitMode.ACTUAL_SIZE,
    readingDirection = ReadingDirection.LEFT_TO_RIGHT,
    keepScreenOn = false,
    showProgressIndicator = false,
    layout = ReaderLayout.SCROLL,
    progressScope = ProgressScope.CHAPTER,
    tapToTurnPages = false,
    reverseTapZones = true,
    pageTurnEffect = PageTurnEffect.FADE,
    hapticsEnabled = false,
    bubbleZoom = false,
)

/**
 * Tests for the settings screen's own reset, which is deliberately not the reader's.
 *
 * This screen shows an appearance, a language and an about section — no reading controls at all.
 * A "reset" on it that wrote the whole default object back would therefore be a button whose
 * effect is mostly *invisible*, quietly undoing reading preferences the reader set somewhere else
 * and has no reason to expect this screen to touch.
 */
class ResetInterfaceDefaultsTest {

    private val repository = FakeSettingsRepository()
    private val updateSettings = UpdateSettingsUseCase(repository)

    private fun afterReset(): ReaderSettings = runBlocking {
        repository.set(everythingChanged)
        updateSettings.resetToDefaults()
        repository.currentSettings()
    }

    @Test
    fun `the appearance and the language go back to their defaults`() {
        val reset = afterReset()
        val defaults = ReaderSettings.Default

        assertEquals("theme", defaults.themeMode, reset.themeMode)
        assertEquals("colour", defaults.colorSource, reset.colorSource)
        assertEquals("language", defaults.language, reset.language)
    }

    /**
     * The half of the rule that is easy to lose, and the reason this reset was narrowed.
     *
     * Compared as a whole object rather than field by field: that is the only form that also covers
     * the field nobody thought about, including the next one added to the model.
     */
    @Test
    fun `nothing outside the interface changes`() {
        assertEquals(
            "only the theme, the colour and the language may differ after a reset",
            everythingChanged.copy(
                themeMode = ReaderSettings.Default.themeMode,
                colorSource = ReaderSettings.Default.colorSource,
                language = ReaderSettings.Default.language,
            ),
            afterReset(),
        )
    }

    /** With the reasons spelled out, because this one is a decision rather than a consequence. */
    @Test
    fun `the reading settings and the library layout are left alone`() {
        val reset = afterReset()

        assertEquals("the margins are a reading setting", 2.0f, reset.marginScale, 0.0001f)
        assertEquals(
            "the paragraph spacing is a reading setting",
            2.5f,
            reset.paragraphSpacingScale,
            0.0001f,
        )
        assertTrue("the first-line indent is a reading setting", reset.firstLineIndent)
        assertEquals("the page turn effect is a reading setting", PageTurnEffect.FADE, reset.pageTurnEffect)
        assertEquals("the shelf's layout is the shelf's", ViewMode.LIST, reset.viewMode)
        assertEquals("the shelf's order is the shelf's", LibrarySort.TITLE_DESC, reset.librarySort)
    }

    /** The first-run question has been answered, and being asked it again would read as a bug. */
    @Test
    fun `the answered colour setup is kept`() {
        assertTrue(afterReset().setupComplete)
    }
}

/**
 * Tests for the reader's own reset, which is deliberately not the app's.
 *
 * The reader's settings panel offers a way back from a font size that has made a book unreadable.
 * The temptation is to implement it as `resetToDefaults()` and be done — and that is the bug this
 * pins down: it would also put the app's language back to Arabic and the library back to a grid,
 * which is not what someone who has just made their text too large is asking for, and is startling
 * enough to make them stop trusting the button.
 */
class ResetReaderDefaultsTest {

    private val repository = FakeSettingsRepository()
    private val updateSettings = UpdateSettingsUseCase(repository)

    private fun afterReset(): ReaderSettings = runBlocking {
        repository.set(everythingChanged)
        updateSettings.resetReaderDefaults()
        repository.currentSettings()
    }

    @Test
    fun `every reading setting goes back to its default`() {
        val reset = afterReset()
        val defaults = ReaderSettings.Default

        assertEquals(defaults.readerFont, reset.readerFont)
        assertEquals("font size", defaults.fontScale, reset.fontScale, 0.0001f)
        assertEquals("line height", defaults.lineHeightScale, reset.lineHeightScale, 0.0001f)
        assertEquals("margins", defaults.marginScale, reset.marginScale, 0.0001f)
        assertEquals(
            "paragraph spacing",
            defaults.paragraphSpacingScale,
            reset.paragraphSpacingScale,
            0.0001f,
        )
        assertEquals("first-line indent", defaults.firstLineIndent, reset.firstLineIndent)
        assertEquals("text alignment", defaults.textAlign, reset.textAlign)
        assertEquals(defaults.pageFitMode, reset.pageFitMode)
        assertEquals(defaults.readingDirection, reset.readingDirection)
        assertEquals(defaults.keepScreenOn, reset.keepScreenOn)
        assertEquals(defaults.showProgressIndicator, reset.showProgressIndicator)
        assertEquals(defaults.layout, reset.layout)
        assertEquals("the progress scope is a reading setting", defaults.progressScope, reset.progressScope)
        assertEquals(defaults.tapToTurnPages, reset.tapToTurnPages)
        assertEquals("the reversed tap zones are a reading setting", defaults.reverseTapZones, reset.reverseTapZones)
        assertEquals("the turn effect is a reading setting", defaults.pageTurnEffect, reset.pageTurnEffect)
        assertEquals("haptics are a reading setting", defaults.hapticsEnabled, reset.hapticsEnabled)
        assertEquals("bubble zoom is a reading setting", defaults.bubbleZoom, reset.bubbleZoom)
    }

    /** The half of the rule that is easy to lose, and the reason this reset exists separately. */
    @Test
    fun `the app's appearance, language and library layout are left alone`() {
        val reset = afterReset()

        assertEquals(ThemeMode.DARK, reset.themeMode)
        assertEquals("the colour is an appearance setting", ColorSource.ROSE, reset.colorSource)
        assertEquals(AppLanguage.ENGLISH, reset.language)
        assertEquals(ViewMode.LIST, reset.viewMode)
        assertEquals(LibrarySort.TITLE_DESC, reset.librarySort)
    }
}

/**
 * A [SettingsRepository] that keeps the settings in memory.
 *
 * A hand-written fake rather than a mocking framework: the interface is two methods, and a fake
 * whose `update` really applies the transform verifies that the use case's `copy` behaves as the
 * screen expects — which a recorded-call assertion would not.
 */
private class FakeSettingsRepository : SettingsRepository {

    private val state = MutableStateFlow(ReaderSettings.Default)

    override val settings: Flow<ReaderSettings> = state

    override suspend fun currentSettings(): ReaderSettings = state.value

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        state.value = transform(state.value)
    }

    /** Test-only seeding, so each case can start from the state it is about. */
    fun set(settings: ReaderSettings) {
        state.value = settings
    }
}
