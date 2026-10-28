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

    // Debug is a side-by-side preview so it cannot overwrite the installed app.
    // Release builds keep the original applicationId.
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dashboardtest"
            versionNameSuffix = "-preview"
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
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
}
