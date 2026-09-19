package com.mylibrary.core.domain

import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.repository.SettingsRepository
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The continuous controls' own arithmetic: what a value outside the offered range means.
 *
 * Every one of these is a slider, and a slider can be dragged past its end — or, more importantly,
 * a stored value can arrive from a build whose range was different. Clamping is what makes that a
 * slightly extreme preference rather than a page of text set to a negative size.
 *
 * The ranges are read from [UpdateSettingsUseCase]'s own constants rather than written out again
 * here, because a second copy of `0.7f..3.0f` in a test would agree with the code right up until
 * someone changed one of them — which is exactly the moment the test is supposed to speak up. What
 * is *not* derived is the direction of the clamp, and the table is written so that both ends are
 * exercised for every slider.
 *
 * Each case starts from a value that is neither the clamp result nor the input, so a setter that
 * did nothing at all could not pass by leaving the state where it already was.
 */
class SliderRangeTest {

    private val repository = FakeSettingsRepository()
    private val updateSettings = UpdateSettingsUseCase(repository)

    @Test
    fun `a value below the range is raised to its floor`() = runTest {
        SLIDERS.forEach { slider ->
            val seeded = slider.min + 0.123f
            assertEquals(
                "${slider.name}: a value under ${slider.min} must not go through",
                slider.min,
                slider.setAndRead(seeded - 1f, seeded),
                0.0001f,
            )
        }
    }

    @Test
    fun `a value above the range is lowered to its ceiling`() = runTest {
        SLIDERS.forEach { slider ->
            val seeded = slider.max - 0.123f
            assertEquals(
                "${slider.name}: a value over ${slider.max} must not go through",
                slider.max,
                slider.setAndRead(seeded + 1f, seeded),
                0.0001f,
            )
        }
    }

    @Test
    fun `a value inside the range is stored as given`() = runTest {
        SLIDERS.forEach { slider ->
            val wanted = (slider.min + slider.max) / 2f

            assertEquals(
                "${slider.name}: the middle of the range must survive untouched",
                wanted,
                slider.setAndRead(wanted, seeded = slider.max),
                0.0001f,
            )
        }
    }

    /**
     * The one range whose floor is not a matter of arithmetic.
     *
     * A margin of zero would put the text against the edge of the display, which is why that floor
     * is `0.5`. No space between paragraphs is different: it is a legitimate way to set a book, and
     * the one a document that separates its own blocks wants — so the honest check is not that the
     * constant says `0f` but that a reader dragging to the end actually arrives there.
     */
    @Test
    fun `a range that begins at zero is reachable at zero`() = runTest {
        val slider = SLIDERS.first { it.name == "paragraph spacing" }

        assertEquals(0f, slider.setAndRead(wanted = 0f, seeded = 1f), 0.0001f)
    }

    /** Seeds a distinguishable value, applies [wanted], and reports what the slider now reads. */
    private suspend fun Slider.setAndRead(wanted: Float, seeded: Float): Float {
        repository.set(ReaderSettings.Default)
        updateSettings.set(seeded)
        updateSettings.set(wanted)
        return read(repository.currentSettings())
    }

    private data class Slider(
        val name: String,
        val min: Float,
        val max: Float,
        val set: suspend UpdateSettingsUseCase.(Float) -> Unit,
        val read: (ReaderSettings) -> Float,
    )

    private companion object {
        /**
         * One entry per continuous setting. A new slider added to the reader's panel and left out of
         * this list is one whose range is untested — the tables in the reader's own tests cover what
         * the slider *means*, not what happens past its end.
         */
        private val SLIDERS = listOf(
            Slider(
                name = "font size",
                min = UpdateSettingsUseCase.MIN_FONT_SCALE,
                max = UpdateSettingsUseCase.MAX_FONT_SCALE,
                set = { setFontScale(it) },
                read = { it.fontScale },
            ),
            Slider(
                name = "line spacing",
                min = UpdateSettingsUseCase.MIN_LINE_HEIGHT,
                max = UpdateSettingsUseCase.MAX_LINE_HEIGHT,
                set = { setLineHeightScale(it) },
                read = { it.lineHeightScale },
            ),
            Slider(
                name = "margins",
                min = UpdateSettingsUseCase.MIN_MARGIN,
                max = UpdateSettingsUseCase.MAX_MARGIN,
                set = { setMarginScale(it) },
                read = { it.marginScale },
            ),
            Slider(
                name = "paragraph spacing",
                min = UpdateSettingsUseCase.MIN_PARAGRAPH_SPACING,
                max = UpdateSettingsUseCase.MAX_PARAGRAPH_SPACING,
                set = { setParagraphSpacingScale(it) },
                read = { it.paragraphSpacingScale },
            ),
        )
    }
}

/** A [SettingsRepository] that keeps the settings in memory, applying each transform for real. */
private class FakeSettingsRepository : SettingsRepository {

    private val state = MutableStateFlow(ReaderSettings.Default)

    override val settings: Flow<ReaderSettings> = state

    override suspend fun currentSettings(): ReaderSettings = state.value

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        state.value = transform(state.value)
    }

    fun set(settings: ReaderSettings) {
        state.value = settings
    }
}
