package com.mylibrary.buildlogic

import org.gradle.api.JavaVersion

/**
 * Java/Kotlin language level shared by every module.
 *
 * We deliberately avoid Gradle *toolchains* here: this environment only ships JDK 21 and toolchain
 * auto-provisioning would need to reach the network. Instead the daemon's JDK 21 compiles down to
 * the Java 17 bytecode level, which is what the Android toolchain (D8/R8) consumes.
 */
object KotlinConfig {
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    const val JVM_TARGET = "17"
}
