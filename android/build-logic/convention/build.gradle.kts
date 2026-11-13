plugins {
    `kotlin-dsl`
}

group = "app.seerah.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.licensee.gradlePlugin)
    compileOnly(libs.moduleGraphAssert.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "seerah.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "seerah.android.application.compose"
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("hilt") {
            id = "seerah.hilt"
            implementationClass = "HiltConventionPlugin"
        }
    }
}
