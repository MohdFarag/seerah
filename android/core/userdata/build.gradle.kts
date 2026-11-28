plugins {
    alias(libs.plugins.seerah.android.library)
    alias(libs.plugins.seerah.hilt)
}

android {
    namespace = "app.seerah.userdata"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:domain"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlin.test)
    androidTestRuntimeOnly(libs.androidx.test.runner)
}
