// Root build: plugin versions for all modules, and the checks that span the whole project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
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
// DAGP itself is applied to every module by the convention plugins; this block only configures it.
dependencyAnalysis {
    structure {
        // Hilt-generated code (Hilt_*, the Dagger component) refers to these types, which hilt-android
        // brings in. Treat them as part of hilt-android rather than declaring Hilt's internals in each module.
        // This also hides direct ViewModel, fragment and annotation use in Hilt modules: an accepted trade-off,
        // to revisit when the first ViewModel module lands.
        bundle("hilt") {
            primary("com.google.dagger:hilt-android")
            includeDependency("com.google.dagger:dagger")
            includeDependency("com.google.dagger:hilt-core")
            includeDependency("javax.inject:javax.inject")
            includeDependency("androidx.annotation:annotation")
            includeDependency("androidx.fragment:fragment")
            includeDependency("androidx.lifecycle:lifecycle-viewmodel")
            includeDependency("androidx.lifecycle:lifecycle-viewmodel-savedstate")
        }
    }
    issues {
        all {
            onAny { severity("fail") }
        }
    }
}
