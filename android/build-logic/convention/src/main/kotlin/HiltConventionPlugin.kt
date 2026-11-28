import app.seerah.buildlogic.library
import app.seerah.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt dependency injection with KSP (ADR 0003). */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            // Library modules emit public Hilt wrapper classes referencing SingletonComponent.
            pluginManager.withPlugin("com.android.application") {
                dependencies { add("implementation", libs.library("hilt-android")) }
            }
            pluginManager.withPlugin("com.android.library") {
                dependencies { add("api", libs.library("hilt-android")) }
            }
            dependencies {
                add("ksp", libs.library("hilt-compiler"))
            }
        }
    }
}
