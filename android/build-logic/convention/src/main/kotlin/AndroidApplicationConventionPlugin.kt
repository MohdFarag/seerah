import app.seerah.buildlogic.configureAndroid
import app.seerah.buildlogic.configureKotlin
import app.seerah.buildlogic.configureLicences
import app.seerah.buildlogic.configureModuleGraphRules
import app.seerah.buildlogic.enforceMapLibreBoundary
import app.seerah.buildlogic.intVersion
import app.seerah.buildlogic.libs
import com.android.build.api.dsl.ApplicationExtension
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
            }
            configureKotlin()
            configureLicences()
            configureModuleGraphRules()
            enforceMapLibreBoundary()
        }
    }
}
