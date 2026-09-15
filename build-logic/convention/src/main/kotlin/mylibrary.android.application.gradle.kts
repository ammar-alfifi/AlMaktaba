import com.android.build.api.dsl.ApplicationExtension
import com.mylibrary.buildlogic.configureMyLibraryAndroid

/**
 * Convention plugin for the single `:app` module.
 *
 * Kotlin support comes from AGP's built-in Kotlin integration (see the library convention plugin
 * for why `org.jetbrains.kotlin.android` must not be applied here). `:app` owns the manifest, the
 * navigation host and Hilt's component wiring, and applies Compose and Hilt separately so those
 * concerns stay visible at the call site.
 */
plugins {
    id("com.android.application")
}

extensions.configure<ApplicationExtension> { configureMyLibraryAndroid() }
