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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
