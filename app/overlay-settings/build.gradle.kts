plugins {
    alias(libs.plugins.android.application)
}

// Оверлей ресурсов (RRO) для стоковых настроек XGIMI: в прошивке у них нет перевода —
// базовые строки китайские. Только строки, без кода; выключается `cmd overlay disable`.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "os.z7.overlay.settings"
    compileSdk = 34

    defaultConfig {
        applicationId = "os.z7.overlay.settings"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
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
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        // имена строк — чужие (из com.android.newsettings), у нас они «не используются»
        disable += "UnusedResources"
        disable += "MissingTranslation"
        abortOnError = false
    }
}
