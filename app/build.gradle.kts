plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.glasstv.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.glasstv.launcher"
        minSdk = 23
        targetSdk = 32
        versionCode = 2
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
