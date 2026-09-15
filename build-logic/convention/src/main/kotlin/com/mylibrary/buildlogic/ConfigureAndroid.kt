package com.mylibrary.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.mylibrary.buildlogic.AndroidConfig
import com.mylibrary.buildlogic.KotlinConfig

/**
 * The Android compilation settings that *every* module in MyLibrary shares.
 *
 * Two overloads rather than one `CommonExtension` helper on purpose: in AGP 9 the shared
 * `CommonExtension` interface exposes only property getters, while the Action-based DSL
 * (`defaultConfig { }`, `compileOptions { }`, ...) lives on the concrete extension types.
 * Overloading keeps the convention plugins declarative and stays compile-checked.
 *
 * Setting `compileOptions.targetCompatibility` here also fixes Kotlin's `jvmTarget`, because
 * AGP's built-in Kotlin defaults it to the Java target.
 */

internal fun LibraryExtension.configureMyLibraryAndroid() {
    compileSdk = AndroidConfig.COMPILE_SDK

    defaultConfig {
        minSdk = AndroidConfig.MIN_SDK
    }

    compileOptions {
        sourceCompatibility = KotlinConfig.JAVA_VERSION
        targetCompatibility = KotlinConfig.JAVA_VERSION
    }

    packaging {
        resources {
            excludes += COMMON_PACKAGING_EXCLUDES
        }
    }

    lint {
        abortOnError = false
        checkDependencies = true
        warningsAsErrors = false
    }
}

internal fun ApplicationExtension.configureMyLibraryAndroid() {
    compileSdk = AndroidConfig.COMPILE_SDK

    defaultConfig {
        minSdk = AndroidConfig.MIN_SDK
        targetSdk = AndroidConfig.TARGET_SDK
        testInstrumentationRunner = AndroidConfig.TEST_INSTRUMENTATION_RUNNER
    }

    compileOptions {
        sourceCompatibility = KotlinConfig.JAVA_VERSION
        targetCompatibility = KotlinConfig.JAVA_VERSION
    }

    packaging {
        resources {
            excludes += COMMON_PACKAGING_EXCLUDES
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkDependencies = true
        warningsAsErrors = false
    }
}

private val COMMON_PACKAGING_EXCLUDES = setOf(
    "/META-INF/{AL2.0,LGPL2.1}",
    "/META-INF/DEPENDENCIES",
    "/META-INF/LICENSE*",
    "/META-INF/NOTICE*",
    "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
)
