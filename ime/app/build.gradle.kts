plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openstation.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openstation.ime"
        minSdk = 26
        targetSdk = 28
        versionCode = 7
        versionName = "1.3.6"
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
