import app.seerah.buildlogic.library
import app.seerah.buildlogic.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Jetpack Compose for a library module: compiler plugin and BOM, without preview tooling. */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<LibraryExtension> {
                buildFeatures.compose = true
            }
            dependencies {
                val bom = platform(libs.library("androidx-compose-bom"))
                add("implementation", bom)
                add("testImplementation", bom)
            }
        }
    }
}
