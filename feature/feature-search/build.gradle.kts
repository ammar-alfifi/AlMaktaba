plugins {
    id("mylibrary.android.feature")
}

android {
    namespace = "com.mylibrary.feature.search"
}

dependencies {
    // The snippet-offset and result-grouping helpers in `SearchResults.kt` are pure Kotlin, so they
    // are covered by plain JVM unit tests rather than on a device. JUnit is the same test runner
    // every other module in the project uses.
    testImplementation(libs.junit)
}
