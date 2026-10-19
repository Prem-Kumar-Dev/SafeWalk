plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.safewalk.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.safewalk"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Static data folder on GitHub Pages, with the jsDelivr mirror as fallback (app-spec/02-data-files.md).
        buildConfigField("String", "DATA_BASE", "\"https://prem-kumar-dev.github.io/SafeWalk/v1/chicago/\"")
        buildConfigField("String", "DATA_MIRROR", "\"https://cdn.jsdelivr.net/gh/Prem-Kumar-Dev/SafeWalk@main/v1/chicago/\"")
        buildConfigField("String", "DEFAULT_STYLE", "\"https://tiles.openfreemap.org/styles/liberty\"")
        buildConfigField("String", "DARK_STYLE", "\"https://tiles.openfreemap.org/styles/dark\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // the bundled data files are already DEFLATE-compressed
        noCompress += "bin"
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
}
