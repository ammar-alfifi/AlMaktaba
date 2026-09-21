import java.util.Properties

plugins {
    id("mylibrary.android.application")
    id("mylibrary.android.compose")
    id("mylibrary.android.hilt")
}

/**
 * Release signing is **opt-in**.
 *
 * The keystore path and its passwords live in `keystore.properties` at the repository root, which
 * is gitignored along with the keystore itself. When that file is absent — which is the case for
 * anyone who clones this repository — the release build still runs and simply produces an unsigned
 * APK. That keeps a fresh clone buildable with no secret material, while letting a real release be
 * signed by dropping one file in place.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.mylibrary"

    defaultConfig {
        applicationId = "com.mylibrary"
        versionCode = 12
        versionName = "1.8.1"
        vectorDrawables { useSupportLibrary = true }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // Both schemes: v1 for API < 24 compatibility, v2/v3 for modern verification.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // Robolectric needs the merged manifest and resources to construct an Android environment
        // for the startup test.
        unitTests.isIncludeAndroidResources = true
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
    // The startup smoke test builds the real Hilt graph and launches MainActivity on the JVM. That
    // is the only way to exercise the whole cold-start path on a machine where no emulator can
    // boot, and it catches the class of failure that compiles cleanly and crashes on launch.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
}
