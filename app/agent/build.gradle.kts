plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Постоянный ключ подписи берётся из окружения (GitHub Secrets → CI).
// Без него (локальная сборка) используется debug-ключ.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "os.z7.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "os.z7.agent"
        minSdk = 28
        targetSdk = 28
        versionCode = 6
        versionName = "0.2.2"
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
    implementation(libs.kotlinx.coroutines)
    implementation(libs.dadb)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
