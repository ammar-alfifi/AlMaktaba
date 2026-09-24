plugins {
    id("mylibrary.android.feature")
}

android {
    namespace = "com.mylibrary.feature.library"
}

dependencies {
    // Storage Access Framework access for importing books and folders.
    implementation(libs.androidx.documentfile)

    // The folder-availability helper in `LibraryViewModel.kt` is pure Kotlin, so the set it marks
    // is covered by plain JVM unit tests rather than on a device.
    testImplementation(libs.junit)
}
