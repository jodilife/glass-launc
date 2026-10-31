plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional shared debug key.
// Place the same debug.keystore here on the developer machine and restore it
// from GitHub Actions Secrets in CI. Without the file, AGP's normal debug key
// remains the fallback so fresh checkouts can still build.
val stableDebugKeystoreFile = file("debug.keystore")
val stableDebugStorePassword = providers.gradleProperty("glasstvDebugStorePassword")
    .orElse(providers.environmentVariable("GLASSTV_DEBUG_STORE_PASSWORD"))
    .orElse("android")
val stableDebugKeyAlias = providers.gradleProperty("glasstvDebugKeyAlias")
    .orElse(providers.environmentVariable("GLASSTV_DEBUG_KEY_ALIAS"))
    .orElse("androiddebugkey")
val stableDebugKeyPassword = providers.gradleProperty("glasstvDebugKeyPassword")
    .orElse(providers.environmentVariable("GLASSTV_DEBUG_KEY_PASSWORD"))
    .orElse("android")

android {
    namespace = "id.glasstv.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.glasstv.launcher"
        minSdk = 23
        targetSdk = 32
        versionCode = 3
        versionName = "0.4.0"
    }

    signingConfigs {
        if (stableDebugKeystoreFile.isFile) {
            create("stableDebug") {
                storeFile = stableDebugKeystoreFile
                storePassword = stableDebugStorePassword.get()
                keyAlias = stableDebugKeyAlias.get()
                keyPassword = stableDebugKeyPassword.get()
            }
        }
    }

    // Debug is a side-by-side preview so it cannot overwrite the installed app.
    // Release builds keep the original applicationId.
    buildTypes {
        getByName("debug") {
            if (stableDebugKeystoreFile.isFile) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
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
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.9.4")
    implementation("androidx.media3:media3-ui:1.9.4")
}
