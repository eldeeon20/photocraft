plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath = (project.findProperty("photocraftStoreFile") as String?)
    ?: System.getenv("PHOTOCRAFT_KEYSTORE")

android {
    namespace = "ai.storyteller.photocraft"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.storyteller.photocraft"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("ci") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PHOTOCRAFT_KEYSTORE_PASSWORD") ?: "photocraft-ci"
                keyAlias = System.getenv("PHOTOCRAFT_KEY_ALIAS") ?: "photocraft-ci"
                keyPassword = System.getenv("PHOTOCRAFT_KEY_PASSWORD") ?: "photocraft-ci"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
