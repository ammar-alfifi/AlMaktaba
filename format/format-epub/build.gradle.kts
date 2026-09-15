plugins {
    id("mylibrary.android.library")
}

android {
    namespace = "com.mylibrary.format.epub"
}

// EPUB 2/3 container parsing: OCF/OPF manifest + spine + NCX/nav navigation, built directly on
// java.util.zip and jsoup. No Hilt and no Compose here.
dependencies {
    api(project(":core:core-domain"))
    implementation(project(":core:core-common"))
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
