plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Tests read the real spike packs produced by ../pack/build.py.
    systemProperty("seerah.packDist", rootDir.resolve("../pack/dist").absolutePath)
    systemProperty("seerah.packSrc", rootDir.resolve("../pack/src").absolutePath)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
