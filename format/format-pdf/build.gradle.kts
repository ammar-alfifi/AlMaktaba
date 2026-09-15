plugins {
    id("mylibrary.android.library")
}

android {
    namespace = "com.mylibrary.format.pdf"
}

// PDF decoding behind `PdfEngine`. Deliberately has no Hilt and no Compose: `:core:core-data`
// constructs these engines and exposes them to the app through the domain interfaces.
dependencies {
    api(project(":core:core-domain"))
    implementation(project(":core:core-common"))
    implementation(libs.pdfium)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
