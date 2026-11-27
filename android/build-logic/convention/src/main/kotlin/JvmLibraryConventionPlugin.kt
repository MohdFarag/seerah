import app.seerah.buildlogic.JAVA_TARGET
import app.seerah.buildlogic.configureLicences
import app.seerah.buildlogic.enforceMapLibreBoundary
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * A pure-JVM Kotlin library module: Java 17 bytecode, Kotlin warnings as errors, the licence
 * check, the MapLibre boundary and dependency analysis (`buildHealth`).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("app.cash.licensee")
            pluginManager.apply("com.autonomousapps.dependency-analysis")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JAVA_TARGET
                targetCompatibility = JAVA_TARGET
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions {
                    jvmTarget.set(JvmTarget.fromTarget(JAVA_TARGET.toString()))
                    allWarningsAsErrors.set(true)
                }
            }
            configureLicences()
            enforceMapLibreBoundary()
        }
    }
}
