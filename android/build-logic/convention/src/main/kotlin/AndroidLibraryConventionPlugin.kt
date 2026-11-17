import app.seerah.buildlogic.configureAndroid
import app.seerah.buildlogic.configureKotlin
import app.seerah.buildlogic.configureLicences
import app.seerah.buildlogic.enforceMapLibreBoundary
import app.seerah.buildlogic.intVersion
import app.seerah.buildlogic.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * An Android library module: SDK levels, lint, Kotlin settings, the licence check, the
 * MapLibre boundary and dependency analysis (`buildHealth`). The module dependency rules run from `:app`, which sees the whole graph.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("app.cash.licensee")
            pluginManager.apply("com.autonomousapps.dependency-analysis")

            extensions.configure<LibraryExtension> {
                configureAndroid(this)
                lint.targetSdk = libs.intVersion("targetSdk")
                testOptions.targetSdk = libs.intVersion("targetSdk")
            }
            configureKotlin()
            configureLicences()
            enforceMapLibreBoundary()
        }
    }
}
