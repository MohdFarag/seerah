package app.seerah.buildlogic

import app.cash.licensee.LicenseeExtension
import com.jraska.module.graph.assertion.GraphRulesExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The only module allowed to depend on MapLibre (ADR 0003). */
private const val MAP_MODULE = ":core:map"
private const val MAPLIBRE_GROUP = "org.maplibre.gl"

/** Licences a runtime dependency may carry (SPDX identifiers, ADR 0004). */
private val ALLOWED_LICENCES = listOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause")

/**
 * Module dependency rules from ADR 0003, checked by `assertModuleGraph` (part of `check`).
 * Every dependency between modules must match an `allowed` rule and no `restricted` rule.
 */
internal fun Project.configureModuleGraphRules() {
    extensions.configure<GraphRulesExtension> {
        maxHeight = 6
        allowed = arrayOf(
            ":app -> :(core|feature):.*",
            ":feature:.* -> :core:.*",
            ":core:.* -> :core:.*",
        )
        restricted = arrayOf(
            ":feature:.* -X> :feature:.*",
            ":core:model -X> :.*",
            ":core:designsystem -X> :.*",
            ":core:(model|domain) -X> :core:(map|designsystem|ui|navigation|update)",
        )
    }
}

/** Fails the build when a module other than `:core:map` declares a MapLibre dependency. */
internal fun Project.enforceMapLibreBoundary() {
    if (path == MAP_MODULE) return
    val modulePath = path
    configurations.configureEach {
        dependencies.configureEach {
            if (group == MAPLIBRE_GROUP) {
                throw GradleException(
                    "$modulePath depends on $group:$name. Only $MAP_MODULE may use MapLibre (ADR 0003).",
                )
            }
        }
    }
}

/** Fails the build when a runtime dependency has a licence outside the allowed list. */
internal fun Project.configureLicences() {
    extensions.configure<LicenseeExtension> {
        ALLOWED_LICENCES.forEach(::allow)
    }
}
