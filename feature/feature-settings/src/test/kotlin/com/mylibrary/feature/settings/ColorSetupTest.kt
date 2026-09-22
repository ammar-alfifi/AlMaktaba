package com.mylibrary.feature.settings

import com.mylibrary.core.domain.model.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for how the theme cards decide which miniature to draw.
 *
 * The bug this pins down is small and visible on any phone already in dark mode: the SYSTEM card was
 * drawn light whatever the device was doing, because "not explicitly dark" was read as "light". The
 * card that exists to say "whatever your phone is doing" was therefore the one card showing the
 * opposite of it — and it is the default, so it was the first one a reader looked at.
 */
class ColorSetupTest {

    @Test
    fun `the system card follows the device, in both directions`() {
        assertTrue("a dark phone must draw the SYSTEM card dark", ThemeMode.SYSTEM.isDark(true))
        assertFalse("a light phone must draw the SYSTEM card light", ThemeMode.SYSTEM.isDark(false))
    }

    @Test
    fun `the explicit modes ignore the device`() {
        assertTrue(ThemeMode.DARK.isDark(systemInDarkTheme = false))
        assertTrue(ThemeMode.DARK.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemInDarkTheme = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemInDarkTheme = true))
    }
}
