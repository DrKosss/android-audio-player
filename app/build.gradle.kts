plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saptac.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.saptac.app"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("saptac") {
            storeFile = file("../signing/saptac.jks")
            storePassword = "saptac2026"
            keyAlias = "saptac"
            keyPassword = "saptac2026"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("saptac") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("saptac")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
}
