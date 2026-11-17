plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.perso.ytmp3"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.perso.ytmp3"
        minSdk = 29          // Android 10 et plus
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.2"

        // Téléphones ARM 64 et 32 bits (quasiment tous les téléphones Android)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Clé de signature fixe : chaque nouvelle version s'installe par-dessus l'ancienne.
    signingConfigs {
        create("perso") {
            storeFile = file("ytmp3.keystore")
            storePassword = "ytmp3pass"
            keyAlias = "ytmp3"
            keyPassword = "ytmp3pass"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Requis par youtubedl-android (Python et ffmpeg sont livrés en bibliothèques natives)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
