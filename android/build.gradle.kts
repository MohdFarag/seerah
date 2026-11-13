// Root build: plugin versions for all modules, and the checks that span the whole project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.module.graph.assert) apply false
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.spotless)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    val composeRules = "io.nlopez.compose.rules:ktlint:${libs.versions.composeRules.get()}"
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "spike/**")
        ktlint(ktlintVersion).customRuleSets(listOf(composeRules))
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "spike/**")
        ktlint(ktlintVersion)
    }
}

// Unused or wrongly declared dependencies fail `buildHealth` (ADR 0004).
dependencyAnalysis {
    issues {
        all {
            onAny { severity("fail") }
        }
    }
}
