pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "seerah-spike"

// :core is pure Kotlin/JVM: content model, time states, package install/update, download.
include(":core")

// :app needs the Android SDK. Without one (e.g. a CI box with no SDK), only :core is built.
val localProps = file("local.properties").takeIf { it.exists() }?.readLines().orEmpty()
val sdkDir = localProps.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (sdkDir != null && file(sdkDir).exists()) {
    include(":app")
} else {
    logger.warn("Android SDK not found: building :core only. Set ANDROID_HOME or local.properties sdk.dir to include :app.")
}
