plugins {
    `kotlin-dsl`
}

group = "com.mylibrary.buildlogic"

// Plugins are declared `compileOnly`: they are already on the build classpath because the root
// build declares them (with versions) in its own `plugins {}` block via the version catalog.
// Precompiled script plugins in `src/main/kotlin` are exposed automatically, with the file name
// as the plugin id (e.g. `mylibrary.android.library.gradle.kts` -> `mylibrary.android.library`).
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}
