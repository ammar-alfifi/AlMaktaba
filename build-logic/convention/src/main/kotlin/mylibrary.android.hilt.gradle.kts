import com.mylibrary.buildlogic.addCatalogDependencies

/**
 * Wires Hilt into an Android module: the Dagger/Hilt Gradle plugin plus KSP for code generation.
 *
 * Modules that only *consume* injected types (feature modules, `:core:core-ui`) apply this plugin
 * so their `@Inject` constructors and `@HiltViewModel`s are processed.
 */
plugins {
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

addCatalogDependencies("implementation", "hilt-android")
addCatalogDependencies("ksp", "hilt-compiler")
