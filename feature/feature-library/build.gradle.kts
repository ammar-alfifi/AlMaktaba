plugins {
    id("mylibrary.android.feature")
}

android {
    namespace = "com.mylibrary.feature.library"
}

dependencies {
    // Storage Access Framework access for importing books and folders.
    implementation(libs.androidx.documentfile)
}
