plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val maplibreVersion = "13.7.1"

android {
    namespace = "app.seerah.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.seerah.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
        buildConfigField("String", "MAPLIBRE_VERSION", "\"$maplibreVersion\"")
        // Real devices only. Leaves out the emulator ABIs to keep the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // One APK per map renderer, so both can be installed side by side and compared.
    flavorDimensions += "renderer"
    productFlavors {
        create("vulkan") {
            dimension = "renderer"
            applicationIdSuffix = ".vulkan"
            resValue("string", "app_name", "السيرة · Vulkan")
        }
        create("opengl") {
            dimension = "renderer"
            applicationIdSuffix = ".opengl"
            resValue("string", "app_name", "السيرة · OpenGL")
        }
    }

    buildTypes {
        debug { }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Map data, glyphs and fonts stay in map-data/ and are bundled as assets.
    sourceSets["main"].assets.srcDirs(
        "src/main/assets",
        "../../map-data/historical/spike",
        "../../map-data/style",
        // Built by tools/spike/build_base.sh and not committed. Without it the
        // app still runs, on a plain background.
        "../../map-data/dist",
    )

    // The app copies these out to device storage, so storing them uncompressed
    // only wastes build time.
    androidResources { noCompress += "pmtiles" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    "vulkanImplementation"("org.maplibre.gl:android-sdk:$maplibreVersion")
    "openglImplementation"("org.maplibre.gl:android-sdk-opengl:$maplibreVersion")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
