import com.mylibrary.buildlogic.addCatalogDependencies

/**
 * Convention plugin for a user-facing feature module.
 *
 * A feature bundles the three layers it is allowed to see — `presentation` (Compose screens +
 * ViewModels), `domain` use cases through `:core:core-domain`, and shared UI from `:core:core-ui`.
 * It deliberately does **not** depend on any `:format:*` module: decoding libraries stay behind the
 * domain interfaces so the UI layer never links against a PDF/EPUB/archive decoder.
 */
plugins {
    id("mylibrary.android.library")
    id("mylibrary.android.compose")
    id("mylibrary.android.hilt")
}

dependencies {
    add("implementation", project(":core:core-common"))
    add("implementation", project(":core:core-domain"))
    add("implementation", project(":core:core-ui"))
}

addCatalogDependencies(
    "implementation",
    "androidx-lifecycle-viewmodel-compose",
    "androidx-lifecycle-runtime-compose",
    "androidx-navigation-compose",
    "hilt-navigation-compose",
    "kotlinx-coroutines-android",
)

// Feature screens use the full Material symbol set (sort, grid/list, bookmark, text-format…),
// not just the handful of icons in `material-icons-core`.
addCatalogDependencies("implementation", "androidx-compose-material-icons-extended")
