plugins {
    alias(libs.plugins.seerah.android.application)
    alias(libs.plugins.seerah.android.application.compose)
    alias(libs.plugins.seerah.hilt)
}

android {
    namespace = "app.seerah"

    defaultConfig {
        // Development ID; the owner fixes the final one before the first Play upload (ADR 0003).
        applicationId = "app.seerah"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Arabic is the only locale; the default resources are Arabic (res/resources.properties).
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            // Debug-signed until the beta, so test builds install without a keystore (ADR 0004).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    constraints {
        implementation(libs.androidx.lifecycle.runtime) {
            because("Older lifecycle releases ship lint checks that this Android Gradle Plugin cannot run.")
        }
        testImplementation(libs.androidx.test.espresso.core) {
            because("Compose UI tests pull in Espresso 3.5, which calls an API that Android 17 removed.")
        }
    }

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)

    debugImplementation(libs.leakcanary)

    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
