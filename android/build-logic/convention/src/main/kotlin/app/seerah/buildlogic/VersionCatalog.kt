package app.seerah.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** The `libs` version catalog from `gradle/libs.versions.toml`. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** A library from the catalog; fails the build if the alias is missing. */
internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow {
        IllegalArgumentException("No library '$alias' in libs.versions.toml")
    }

/** A version from the catalog as an integer, used for SDK levels. */
internal fun VersionCatalog.intVersion(alias: String): Int = findVersion(alias)
    .orElseThrow { IllegalArgumentException("No version '$alias' in libs.versions.toml") }
    .requiredVersion
    .toInt()
