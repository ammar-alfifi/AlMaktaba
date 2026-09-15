plugins {
    id("mylibrary.android.feature")
}

android {
    namespace = "com.mylibrary.feature.settings"
}

dependencies {
    // Test-only: the intent-to-settings mapping is pure Kotlin, so it is asserted on the JVM rather
    // than on a device. JUnit is the runner the rest of the build already uses; nothing here ships.
    testImplementation(libs.junit)
}
