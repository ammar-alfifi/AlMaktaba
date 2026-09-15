package com.mylibrary.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * Bridge between the convention plugins and `gradle/libs.versions.toml`.
 *
 * Precompiled script plugins get no generated version-catalog accessors, so the catalog is looked
 * up through the settings extension instead. [library] fails loudly on a typo rather than
 * silently contributing nothing to the classpath.
 */
internal val Project.versionCatalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Resolves a catalog alias such as `androidx-compose-ui` to its dependency provider. */
internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow {
        IllegalStateException(
            "No library alias '$alias' in gradle/libs.versions.toml. " +
                "Available: ${libraryAliases.joinToString()}",
        )
    }

/**
 * Adds each catalog [aliases] entry to [configuration], skipping the ones this module cannot have.
 *
 * Convention plugins are applied to different kinds of module and in different orders (a decoder
 * has no `debugImplementation`, a JVM module has no `ksp`), so probing first keeps every plugin
 * safe to apply anywhere instead of failing with `Configuration not found`.
 */
internal fun Project.addCatalogDependencies(configuration: String, vararg aliases: String) {
    if (configurations.findByName(configuration) == null) return
    aliases.forEach { alias -> dependencies.add(configuration, versionCatalog.library(alias)) }
}
