import com.mylibrary.buildlogic.addCatalogDependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Convention plugin for pure-Kotlin/JVM modules.
 *
 * `:core:core-common` and `:core:core-domain` are plain JVM libraries: business models, repository
 * interfaces, decoder interfaces and use cases with no Android dependency at all. That is what
 * keeps the domain layer testable with plain JUnit and impossible to accidentally entangle with
 * the framework.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

extensions.configure<KotlinJvmProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

addCatalogDependencies("implementation", "kotlinx-coroutines-core")
