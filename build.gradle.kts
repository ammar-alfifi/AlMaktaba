// Root build file.
//
// Plugin versions live in the version catalog and are declared here (with `apply false`) so that
// they land on the build's plugin classpath exactly once — the convention plugins in `build-logic`
// then apply them by id. No `subprojects {}` / `allprojects {}` blocks: per-module configuration
// belongs in `build-logic`, and editing it there is what makes it reviewable.
//
// Note the absence of `org.jetbrains.kotlin.android`: AGP 9 provides Kotlin support itself, and
// applying the standalone Android Kotlin plugin is now an error. Declaring `kotlin.jvm` here keeps
// the Kotlin Gradle Plugin at the version in the catalog for both the JVM modules and AGP's
// built-in Kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
