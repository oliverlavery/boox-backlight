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
}
