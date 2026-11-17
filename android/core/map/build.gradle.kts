plugins {
    alias(libs.plugins.seerah.android.library)
    alias(libs.plugins.seerah.android.library.compose)
}

android {
    namespace = "app.seerah.map"
}

dependencies {
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    implementation(libs.maplibre.android.opengl)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Provides Dispatchers.Main on Android; code refers only to kotlinx-coroutines-core.
    runtimeOnly(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
