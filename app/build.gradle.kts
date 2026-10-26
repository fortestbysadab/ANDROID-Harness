plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidharness"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidharness"
        // 26 covers effectively every device in use and keeps the service
        // and notification APIs this app needs available.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase0"
    }

    buildTypes {
        // Debug only for now: sideloaded by its owner, never published, and
        // a debug signature keeps installs upgradeable without a keystore
        // living in CI.
        getByName("debug") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
