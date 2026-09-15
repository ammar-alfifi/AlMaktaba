import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.mylibrary.buildlogic.addCatalogDependencies

/**
 * Adds Jetpack Compose to an Android module.
 *
 * Compose artifact versions come from the version catalog (pinned to the versions Compose BOM
 * 2026.08.00 resolves to). Tooling and the test manifest are added as `debugImplementation` only,
 * so the Compose inspector never ships in a release build.
 */
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

// AGP 9's DSL is per module kind, so the Compose build feature is enabled on whichever extension
// this module actually has.
extensions.findByType(LibraryExtension::class.java)?.apply {
    buildFeatures { compose = true }
}
extensions.findByType(ApplicationExtension::class.java)?.apply {
    buildFeatures { compose = true }
}

addCatalogDependencies(
    "implementation",
    "androidx-compose-ui",
    "androidx-compose-ui-graphics",
    "androidx-compose-ui-tooling-preview",
    "androidx-compose-foundation",
    "androidx-compose-runtime",
    "androidx-compose-material3",
    "androidx-compose-material-icons-core",
)

addCatalogDependencies(
    "debugImplementation",
    "androidx-compose-ui-tooling",
    "androidx-compose-ui-test-manifest",
)

addCatalogDependencies(
    "androidTestImplementation",
    "androidx-compose-ui-test-junit4",
)
