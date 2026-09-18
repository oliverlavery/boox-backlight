plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.shadow.booxbacklight"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.shadow.booxbacklight"
        minSdk = 30          // Palma 2 Pro ships Android 11+; 30 is safe
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md", "META-INF/LICENSE", "META-INF/NOTICE")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Phase 1: observe + log only. Zero deps on purpose.
    // Phase 2 will add nothing either — rxjava-style flows hand-rolled or kotlinx-coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // ADB client library — lets the app talk to the device's own adbd (localhost:5555)
    // to run shell commands (airplane mode) without root. Experiment branch only.
    implementation("dev.mobile:dadb:1.2.9")
}
