import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maptechgh.adminalerts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maptechgh.adminalerts"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.2.3"
    }

    // Optional release signing: put keystore.properties next to this file
    // (storeFile, storePassword, keyAlias, keyPassword). Without it the
    // release build is signed with the debug key so it still installs.
    val ksFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (ksFile.exists()) {
            val p = Properties()
            ksFile.inputStream().use { p.load(it) }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No extra libraries — the app uses only the Android framework, so it stays small
// and builds anywhere.
