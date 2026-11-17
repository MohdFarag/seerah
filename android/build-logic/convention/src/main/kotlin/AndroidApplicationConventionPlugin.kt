import app.seerah.buildlogic.configureAndroid
import app.seerah.buildlogic.configureKotlin
import app.seerah.buildlogic.configureLicences
import app.seerah.buildlogic.configureModuleGraphRules
import app.seerah.buildlogic.enforceMapLibreBoundary
import app.seerah.buildlogic.enforceMapOnlyInBenchmark
import app.seerah.buildlogic.intVersion
import app.seerah.buildlogic.libs
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * The application module: SDK levels, lint, Kotlin settings, R8 for release builds,
 * the licence check and the module dependency rules.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("app.cash.licensee")
            pluginManager.apply("com.jraska.module.graph.assertion")

            extensions.configure<ApplicationExtension> {
                configureAndroid(this)
                defaultConfig.targetSdk = libs.intVersion("targetSdk")
                buildTypes.getByName("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
                // Release-like build for measuring (ADR 0004 note, owner 2026-11-14); never published.
                buildTypes.create("benchmark") {
                    initWith(buildTypes.getByName("release"))
                    signingConfig = signingConfigs.getByName("debug")
                    applicationIdSuffix = ".benchmark"
                    matchingFallbacks += "release"
                }
            }
            extensions.configure<ApplicationAndroidComponentsExtension> {
                // Unit tests are off for build types the DSL does not test; `check` should run these.
                beforeVariants(selector().withBuildType("benchmark")) { variant ->
                    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
                }
            }
            configureKotlin()
            configureLicences()
            configureModuleGraphRules()
            enforceMapLibreBoundary()
            enforceMapOnlyInBenchmark()
        }
    }
}
