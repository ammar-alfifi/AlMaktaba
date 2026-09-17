package com.mylibrary.feature.settings

import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ReaderLayout
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
 * releases a slider on is not the value they get back. Rather than testing each path separately,
 * every case here asserts all three: the expected settings, the optimistic transform and the
 * persisted result. A mapping edited on one side only fails on the other.
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
    fun `slider values outside the domain range are clamped identically on both paths`() {
        val tooLarge = SettingsIntent.FontScaleChanged(UpdateSettingsUseCase.MAX_FONT_SCALE + 5f)
        val tooSmall = SettingsIntent.LineHeightChanged(UpdateSettingsUseCase.MIN_LINE_HEIGHT - 5f)

        assertEquals(
            UpdateSettingsUseCase.MAX_FONT_SCALE,
            ReaderSettings.Default.updatedBy(tooLarge).fontScale,
        )
        assertEquals(
            UpdateSettingsUseCase.MAX_FONT_SCALE,
            persist(ReaderSettings.Default, tooLarge).fontScale,
        )
        assertEquals(
            UpdateSettingsUseCase.MIN_LINE_HEIGHT,
            ReaderSettings.Default.updatedBy(tooSmall).lineHeightScale,
        )
        assertEquals(
            UpdateSettingsUseCase.MIN_LINE_HEIGHT,
            persist(ReaderSettings.Default, tooSmall).lineHeightScale,
        )
    }

    @Test
    fun `reset returns a settings object that had been changed in every section`() {
        val changed = ReaderSettings.Default.copy(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            language = AppLanguage.ENGLISH,
            viewMode = ViewMode.LIST,
            librarySort = LibrarySort.AUTHOR,
            readerFont = ReaderFont.SERIF,
            fontScale = 2f,
            lineHeightScale = 2f,
            pageFitMode = PageFitMode.ACTUAL_SIZE,
            readingDirection = ReadingDirection.LEFT_TO_RIGHT,
            keepScreenOn = false,
            showProgressIndicator = false,
        )

        assertEquals(
            ReaderSettings.Default,
            changed.updatedBy(SettingsIntent.ResetToDefaults),
        )
        assertEquals(
            ReaderSettings.Default,
            persist(changed, SettingsIntent.ResetToDefaults),
        )
    }

    @Test
    fun `only the two continuous sliders are treated as drags`() {
        // Everything else must take the write-immediately path; if a switch were ever listed here
        // it would gain a debounce delay it does not need. Compared by type rather than by value,
        // because the table above already pins the values down.
        val drags = CASES.map { it.intent }.filter { it.isSliderDrag }

        assertEquals("a drag should reach the coalescing path exactly twice", 2, drags.size)
        assertEquals(
            setOf(SettingsIntent.FontScaleChanged::class, SettingsIntent.LineHeightChanged::class),
            drags.map { it::class }.toSet(),
        )
    }

    @Test
    fun `a persisted intent changes only the field it names`() {
        // The reader's own controls write some of the same fields from another screen; a mapping
        // that quietly reset its neighbours would fight them.
        val before = ReaderSettings.Default.copy(language = AppLanguage.ENGLISH)
        val after = persist(before, SettingsIntent.ThemeModeChanged(ThemeMode.DARK))

        assertTrue("language must survive a theme change", after.language == AppLanguage.ENGLISH)
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
                name = "dynamic colour",
                startFrom = Defaults,
                intent = SettingsIntent.DynamicColorToggled(false),
                expected = Defaults.copy(dynamicColor = false),
            ),
            Case(
                name = "language",
                startFrom = Defaults,
                intent = SettingsIntent.LanguageChanged(AppLanguage.ENGLISH),
                expected = Defaults.copy(language = AppLanguage.ENGLISH),
            ),
            Case(
                name = "view mode",
                startFrom = Defaults,
                intent = SettingsIntent.ViewModeChanged(ViewMode.LIST),
                expected = Defaults.copy(viewMode = ViewMode.LIST),
            ),
            Case(
                name = "sort order",
                startFrom = Defaults,
                intent = SettingsIntent.SortChanged(LibrarySort.AUTHOR),
                expected = Defaults.copy(librarySort = LibrarySort.AUTHOR),
            ),
            Case(
                name = "reader font",
                startFrom = Defaults,
                intent = SettingsIntent.ReaderFontChanged(ReaderFont.SERIF),
                expected = Defaults.copy(readerFont = ReaderFont.SERIF),
            ),
            Case(
                name = "font scale",
                startFrom = Defaults,
                intent = SettingsIntent.FontScaleChanged(1.5f),
                expected = Defaults.copy(fontScale = 1.5f),
            ),
            Case(
                name = "line height",
                startFrom = Defaults,
                intent = SettingsIntent.LineHeightChanged(1.8f),
                expected = Defaults.copy(lineHeightScale = 1.8f),
            ),
            Case(
                name = "page fit",
                startFrom = Defaults,
                intent = SettingsIntent.PageFitChanged(PageFitMode.WIDTH),
                expected = Defaults.copy(pageFitMode = PageFitMode.WIDTH),
            ),
            Case(
                name = "reading direction",
                startFrom = Defaults,
                intent = SettingsIntent.ReadingDirectionChanged(ReadingDirection.RIGHT_TO_LEFT),
                expected = Defaults.copy(readingDirection = ReadingDirection.RIGHT_TO_LEFT),
            ),
            Case(
                name = "keep screen on",
                startFrom = Defaults,
                intent = SettingsIntent.KeepScreenOnToggled(false),
                expected = Defaults.copy(keepScreenOn = false),
            ),
            Case(
                name = "show progress",
                startFrom = Defaults,
                intent = SettingsIntent.ShowProgressToggled(false),
                expected = Defaults.copy(showProgressIndicator = false),
            ),
            Case(
                name = "page snapping",
                startFrom = Defaults,
                intent = SettingsIntent.LayoutChanged(ReaderLayout.SCROLL),
                expected = Defaults.copy(layout = ReaderLayout.SCROLL),
            ),
            Case(
                name = "reset",
                // Started from a changed state so that "reset" cannot pass by doing nothing.
                startFrom = Defaults.copy(themeMode = ThemeMode.DARK, fontScale = 2f),
                intent = SettingsIntent.ResetToDefaults,
                expected = Defaults,
            ),
        )
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

    /** Every field set to something that is *not* its default, so a reset has to do work. */
    private val customised = ReaderSettings(
        themeMode = ThemeMode.DARK,
        dynamicColor = false,
        language = AppLanguage.ENGLISH,
        viewMode = ViewMode.LIST,
        librarySort = LibrarySort.TITLE_DESC,
        readerFont = ReaderFont.SERIF,
        fontScale = 2.4f,
        lineHeightScale = 2.1f,
        pageFitMode = PageFitMode.ACTUAL_SIZE,
        readingDirection = ReadingDirection.LEFT_TO_RIGHT,
        keepScreenOn = false,
        showProgressIndicator = false,
        layout = ReaderLayout.SCROLL,
        tapToTurnPages = false,
        pageTurnEffect = PageTurnEffect.FADE,
        bubbleZoom = false,
    )

    private fun afterReset(): ReaderSettings = runBlocking {
        repository.set(customised)
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
        assertEquals(defaults.pageFitMode, reset.pageFitMode)
        assertEquals(defaults.readingDirection, reset.readingDirection)
        assertEquals(defaults.keepScreenOn, reset.keepScreenOn)
        assertEquals(defaults.showProgressIndicator, reset.showProgressIndicator)
        assertEquals(defaults.layout, reset.layout)
        assertEquals(defaults.tapToTurnPages, reset.tapToTurnPages)
        assertEquals("the turn effect is a reading setting", defaults.pageTurnEffect, reset.pageTurnEffect)
        assertEquals("bubble zoom is a reading setting", defaults.bubbleZoom, reset.bubbleZoom)
    }

    /** The half of the rule that is easy to lose, and the reason this reset exists separately. */
    @Test
    fun `the app's appearance, language and library layout are left alone`() {
        val reset = afterReset()

        assertEquals(ThemeMode.DARK, reset.themeMode)
        assertTrue("dynamic colour was switched back on", !reset.dynamicColor)
        assertEquals(AppLanguage.ENGLISH, reset.language)
        assertEquals(ViewMode.LIST, reset.viewMode)
        assertEquals(LibrarySort.TITLE_DESC, reset.librarySort)
    }
}
