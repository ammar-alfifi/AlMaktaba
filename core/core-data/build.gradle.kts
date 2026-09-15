plugins {
    id("mylibrary.android.library")
    id("mylibrary.android.hilt")
    id("mylibrary.android.room")
}

android {
    namespace = "com.mylibrary.core.data"

    testOptions {
        // Robolectric needs the merged resources and manifest to build an Android environment for
        // the DAO tests, which execute real SQL against a real SQLite database on the JVM.
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

// The only module allowed to see both the domain contracts and the concrete decoder modules:
// it implements the repositories over Room + DataStore and binds every :format engine to its
// domain interface. Nothing above this layer links against a decoder.
dependencies {
    api(project(":core:core-common"))
    api(project(":core:core-domain"))

    implementation(project(":format:format-pdf"))
    implementation(project(":format:format-epub"))
    implementation(project(":format:format-text"))
    implementation(project(":format:format-archive"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
