package com.mylibrary.buildlogic

/**
 * Single source of truth for the Android SDK levels used by every module.
 *
 * `compileSdk` 37 is required, not merely preferred: `io.legere:pdfiumandroid` (the PDF engine)
 * publishes a minimum `compileSdk` of 37, so every module in the graph compiles against Android 17.
 *
 * `targetSdk` deliberately stays at 36. Compiling against the newest API is what unlocks the
 * platform APIs the decoders need, while `targetSdk` is what opts the app into new *runtime*
 * behaviour — and that behaviour is only observable on a device running that release. Keeping it
 * one level behind means every behaviour change the app is subject to can actually be tested.
 *
 * `minSdk` 26 keeps `java.time`, adaptive icons and full vector drawable support available without
 * desugaring or legacy support libraries.
 */
object AndroidConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 26

    const val TEST_INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
}
