pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Pin the build's default locale to US English.
//
// Not cosmetic: on a machine whose locale is Arabic, code generators that format numbers with
// `Locale.getDefault()` write Arabic-Indic digits into generated source. Room emits the schema
// version as a constructor argument, so the generated Kotlin would contain `RoomOpenDelegate(١, …)`
// and fail to compile — an error pointing at a file nobody wrote. The application's own locale is
// unaffected: that is driven by resources and the in-app language setting, not by this.
java.util.Locale.setDefault(java.util.Locale.US)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyLibrary"

include(":app")

// Core modules
include(":core:core-common")
include(":core:core-domain")
include(":core:core-data")
include(":core:core-ui")

// Feature modules
include(":feature:feature-library")
include(":feature:feature-reader")
include(":feature:feature-settings")
include(":feature:feature-search")

// Format (decoder) modules
include(":format:format-pdf")
include(":format:format-epub")
include(":format:format-text")
include(":format:format-archive")
