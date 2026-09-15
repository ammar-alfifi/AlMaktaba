plugins {
    id("mylibrary.android.feature")
}

android {
    namespace = "com.mylibrary.feature.reader"
}

dependencies {
    implementation(libs.androidx.window)

    // The reader turns the sanitised HTML that `:format:format-epub` produces into Compose text
    // blocks. jsoup is a parser, not a decoder — the page-decoding work still happens entirely
    // behind the `:core:core-domain` interfaces.
    implementation(libs.jsoup)
}
