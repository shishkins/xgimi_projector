plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Постоянный ключ подписи берётся из окружения (GitHub Secrets → CI).
// Без него (локальная сборка) используется debug-ключ.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "os.z7.miracast"
    compileSdk = 34

    defaultConfig {
        applicationId = "os.z7.miracast"
        minSdk = 28
        targetSdk = 28
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // постоянный ключ, если задан; иначе debug (локально)
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
        // targetSdk 28 выбран намеренно (фоновая работа без ограничений),
        // в Google Play не публикуемся — отключаем соответствующую проверку.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
