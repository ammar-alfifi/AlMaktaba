plugins {
    id("mylibrary.android.library")
}

android {
    namespace = "com.mylibrary.format.text"
}

// Plain-text decoding: character-set detection (UTF-8/UTF-16/Windows-1256 for Arabic) plus
// direction detection so an English TXT file reads correctly inside an RTL interface.
dependencies {
    api(project(":core:core-domain"))
    implementation(project(":core:core-common"))
    implementation(libs.juniversalchardet)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
