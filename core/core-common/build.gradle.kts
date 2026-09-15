plugins {
    id("mylibrary.kotlin.library")
}

// Pure JVM module: no Android dependency whatsoever. See CoreCommonMarker for details.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
