import com.android.build.api.dsl.LibraryExtension
import com.mylibrary.buildlogic.configureMyLibraryAndroid

/**
 * Convention plugin for every `com.android.library` module in MyLibrary.
 *
 * Usage: `plugins { id("mylibrary.android.library") }` plus an explicit `namespace`.
 *
 * Note there is deliberately no `org.jetbrains.kotlin.android` plugin: since AGP 9 the Kotlin
 * plugin is built in and applying the standalone one is an error. Kotlin's `jvmTarget` follows
 * `android.compileOptions.targetCompatibility`, which [configureMyLibraryAndroid] sets, so the
 * Kotlin language level needs no separate configuration.
 *
 * Compose / Hilt / Room are layered on by their own convention plugins, so a pure decoder module
 * never drags in the Compose runtime.
 */
plugins {
    id("com.android.library")
}

extensions.configure<LibraryExtension> { configureMyLibraryAndroid() }
