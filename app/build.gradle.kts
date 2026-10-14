plugins {
    id("com.android.application")
}

android {
    namespace = "de.laufdoch"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.laufdoch"
        minSdk = 29
        targetSdk = 34
        versionCode = 42
        versionName = "1.41"
    }

    // Fester Schlüssel, damit Updates ohne Deinstallation installiert werden können.
    // Schlüssel, Passwort und Alias kommen aus den GitHub-Secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD und KEY_ALIAS.
    signingConfigs {
        getByName("debug") {
            storeFile = file("laufdoch.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "laufdoch"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
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
}
