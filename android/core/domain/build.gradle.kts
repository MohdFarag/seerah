plugins {
    alias(libs.plugins.seerah.jvm.library)
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}
