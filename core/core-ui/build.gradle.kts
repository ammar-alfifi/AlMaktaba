plugins {
    id("mylibrary.android.library")
    id("mylibrary.android.compose")
}

android {
    namespace = "com.mylibrary.core.ui"
}

// Design system: theme, typography, colour scheme and the shared Compose components every feature
// builds its screens from.
dependencies {
    api(project(":core:core-common"))
    api(project(":core:core-domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The MVI base classes in `com.mylibrary.core.ui.mvi` extend ViewModel.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    // WindowSizeClass, for the adaptive scaffold. The Material 3 `material3-window-size-class`
    // artifact is deliberately not used: it is deprecated in favour of these AndroidX breakpoints.
    // `window-core` is declared explicitly because `window` keeps it off the compile classpath.
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.core)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit)
}
