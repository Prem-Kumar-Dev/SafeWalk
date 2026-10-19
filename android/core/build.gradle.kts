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
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // Point at a real data file to run the full-city tests: -PsafewalkData=/path/to/file
    providers.gradleProperty("safewalkData").orNull?.let { systemProperty("safewalk.data", it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
