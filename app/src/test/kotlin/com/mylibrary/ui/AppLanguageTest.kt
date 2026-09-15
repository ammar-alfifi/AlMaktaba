package com.mylibrary.ui

import android.content.Context
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.mylibrary.R
import com.mylibrary.core.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the in-app language override.
 *
 * These exist because the override is subtle in a way that is easy to break. The app deliberately
 * does **not** replace `LocalContext` — doing so crashes Hilt's ViewModel factory, which needs an
 * Activity context — so localisation runs entirely through the resources carried by a
 * configuration-scoped context. That works, but only as long as [withAppLanguage] really does
 * produce resources in the chosen language, which is exactly what these assert.
 *
 * Resource resolution is checked across module boundaries too: `R.string.app_name` lives in `:app`
 * and `lib_title` in `:feature:feature-library`, and both have to follow the override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLanguageTest {

    private val baseContext: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `Arabic and English resolve different strings`() {
        val arabic = baseContext.withAppLanguage(AppLanguage.ARABIC)
        val english = baseContext.withAppLanguage(AppLanguage.ENGLISH)

        assertEquals("مكتبتي", arabic.getString(R.string.app_name))
        assertEquals("MyLibrary", english.getString(R.string.app_name))
    }

    @Test
    fun `the override reaches another module's resources`() {
        val arabic = baseContext.withAppLanguage(AppLanguage.ARABIC)
        val english = baseContext.withAppLanguage(AppLanguage.ENGLISH)

        assertEquals(
            "لا توجد كتب بعد",
            arabic.getString(com.mylibrary.feature.library.R.string.lib_empty_title),
        )
        assertEquals(
            "No books yet",
            english.getString(com.mylibrary.feature.library.R.string.lib_empty_title),
        )
    }

    @Test
    fun `Arabic selects a right-to-left layout direction and English a left-to-right one`() {
        // This is the whole RTL requirement in one assertion: the direction the Compose tree is
        // given comes from the same configuration as the strings, so they can never disagree.
        assertEquals(
            LayoutDirection.Rtl,
            baseContext.withAppLanguage(AppLanguage.ARABIC)
                .resources.configuration.toComposeLayoutDirection(),
        )
        assertEquals(
            LayoutDirection.Ltr,
            baseContext.withAppLanguage(AppLanguage.ENGLISH)
                .resources.configuration.toComposeLayoutDirection(),
        )
    }

    @Test
    fun `following the system leaves the context untouched`() {
        // "System" must not mean "Arabic by another name": it has to be the un-scoped context, so
        // an app on the setting behaves exactly like one that never had the feature.
        val system = baseContext.withAppLanguage(AppLanguage.SYSTEM)

        assertSame(baseContext, system)
    }

    @Test
    fun `the override does not disturb screen metrics`() {
        // The localized configuration is used by the adaptive layout, so a language change must not
        // be able to alter the window size it measures.
        val original = baseContext.resources.configuration
        val arabic = baseContext.withAppLanguage(AppLanguage.ARABIC).resources.configuration

        assertEquals(original.screenWidthDp, arabic.screenWidthDp)
        assertEquals(original.screenHeightDp, arabic.screenHeightDp)
        assertEquals(original.densityDpi, arabic.densityDpi)
    }
}
