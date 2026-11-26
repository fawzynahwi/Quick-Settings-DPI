plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.qsdpi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.qsdpi"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so `assembleRelease` gives an installable APK
            signingConfig = signingConfigs.getByName("debug")
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
    // If api.xposed.info is down: download api-82.jar, put it in app/libs/ and use
    // compileOnly(files("libs/api-82.jar")) instead.
    compileOnly("de.robv.android.xposed:api:82")
}
