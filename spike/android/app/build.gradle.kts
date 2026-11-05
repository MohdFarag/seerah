plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.seerah.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.seerah.spike"
        minSdk = 26 // spike choice only; the product minimum is an open decision
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    buildTypes {
        // Measure size/perf on release (minified) builds; debug builds are much slower.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // spike only
        }
    }

    // The v1 package is bundled as the first-run package (built by spike/pack/build.py).
    sourceSets["main"].assets.srcDir(rootDir.resolve("../pack/dist/assets"))
    androidResources { noCompress += "zip" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.maplibre.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
}

val seed = rootDir.resolve("../pack/dist/assets/seed-pack.zip")
tasks.named("preBuild") {
    doFirst { check(seed.isFile) { "Missing $seed: run spike/pack/prepare_sources.sh && python3 spike/pack/build.py" } }
}
