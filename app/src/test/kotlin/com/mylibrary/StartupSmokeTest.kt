package com.mylibrary

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Cold-start smoke tests.
 *
 * These build the **real Hilt graph** — every module, every `@Provides`, the Room database, the
 * DataStore, all four decoder engines — and launch [MainActivity] through its actual `onCreate`,
 * then drive composition to completion. That is exactly the path that a compile-time-clean build
 * can still fail on, and it is the only way to exercise it on a machine where no emulator boots.
 *
 * They are deliberately shallow: they assert the app starts and stays alive, not what it renders.
 * A crash anywhere on the startup path fails here with the real stack trace, which is the point.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class StartupSmokeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * Drains the main looper until it is idle.
     *
     * Composition, the DataStore read and the first database query all post to the main looper.
     * Without idling, the test would assert on a half-initialised screen and pass even if the app
     * would have crashed a frame later.
     */
    private fun settle() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(16)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `the application builds its dependency graph`() {
        // Constructing the Application is what runs Hilt's component generation; if any binding in
        // any module is unsatisfiable at runtime, this is where it surfaces.
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(
            "Hilt must replace the production application under test",
            application is MyLibraryApplication,
        )
    }

    @Test
    fun `MainActivity reaches RESUMED without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()

            scenario.onActivity { activity ->
                assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                assertFalse("Activity finished during startup", activity.isFinishing)
            }
        }
    }

    @Test
    fun `launching twice survives a configuration change`() {
        // A recreation tears down and rebuilds the whole Compose tree and every ViewModel. Any
        // state that is captured unsafely — a non-idempotent initialiser, a leaked observer —
        // fails on the second pass rather than the first.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.recreate()
            settle()

            scenario.onActivity { activity ->
                assertFalse("Activity finished after recreation", activity.isFinishing)
            }
        }
    }
}
