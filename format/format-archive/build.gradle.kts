plugins {
    id("mylibrary.android.library")
}

android {
    namespace = "com.mylibrary.format.archive"
}

// Comic-book archives: CBZ through java.util.zip, CBR through junrar. Both produce the same
// ordered page list, including natural ("page2" before "page10") entry ordering.
dependencies {
    api(project(":core:core-domain"))
    implementation(project(":core:core-common"))
    implementation(libs.junrar)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
