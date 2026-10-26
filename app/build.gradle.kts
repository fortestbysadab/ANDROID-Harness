plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
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

        // Without this the APK carries a CPython build for every ABI.
        // arm64 is every phone that matters now; x86_64 keeps emulators
        // working for anyone who tries to reproduce a bug.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Python 3.13 or later is required for devices with 16 KB memory
    // pages, which is the direction Android is moving.
    chaquopy {
        defaultConfig {
            version = "3.13"
        }
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
    testImplementation("junit:junit:4.13.2")

    // android.jar on the unit-test classpath is a stub: every method in
    // it throws "Stub!". That is fine for classes a JVM test has no
    // business touching, but org.json is pure data handling and the
    // tools use it as their boundary format. This puts a real
    // implementation in front of the stub.
    //
    // Android's JSONObject is a fork of this library, not this library,
    // so these tests check our logic rather than the platform's exact
    // coercions - the device confirms the rest.
    testImplementation("org.json:json:20240303")
}
