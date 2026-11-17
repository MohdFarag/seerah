plugins {
    alias(libs.plugins.seerah.android.library)
}

android {
    namespace = "app.seerah.map"
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
