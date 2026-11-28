package app.seerah.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Bytecode level for all modules. Builds run on JDK 21 (ADR 0004). */
internal val JAVA_TARGET = JavaVersion.VERSION_17

/**
 * Lint checks that only report newer versions. Dependabot handles updates, so these
 * would only make the build fail whenever a library publishes a release.
 */
private val VERSION_CHECKS = setOf(
    "AndroidGradlePluginVersion",
    "GradleDependency",
    "NewerVersionAvailable",
    "OldTargetApi",
)

/** SDK levels, Java level and lint rules shared by every Android module. */
internal fun Project.configureAndroid(android: CommonExtension) {
    android.apply {
        compileSdk {
            version = release(libs.intVersion("compileSdk")) {
                minorApiLevel = libs.intVersion("compileSdkMinor")
            }
        }
        defaultConfig.minSdk = libs.intVersion("minSdk")
        compileOptions.apply {
            sourceCompatibility = JAVA_TARGET
            targetCompatibility = JAVA_TARGET
        }
        lint.apply {
            warningsAsErrors = true
            abortOnError = true
            checkDependencies = true
            disable += VERSION_CHECKS
        }
        testOptions.unitTests.isIncludeAndroidResources = true
        // Robolectric needs JDK-internal access to emulate Android 16+ shared memory in tests.
        testOptions.unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

/** Kotlin compiler settings: warnings fail the build (docs/engineering.md). */
internal fun Project.configureKotlin() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(JAVA_TARGET.toString()))
            allWarningsAsErrors.set(true)
        }
    }
}
