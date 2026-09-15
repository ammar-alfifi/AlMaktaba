plugins {
    id("mylibrary.android.application")
    id("mylibrary.android.compose")
    id("mylibrary.android.hilt")
}

android {
    namespace = "com.mylibrary"

    defaultConfig {
        applicationId = "com.mylibrary"
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))

    implementation(project(":feature:feature-library"))
    implementation(project(":feature:feature-reader"))
    implementation(project(":feature:feature-settings"))
    implementation(project(":feature:feature-search"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
}
