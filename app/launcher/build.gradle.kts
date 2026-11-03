plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Тот же постоянный ключ, что у агента: модули доверяют друг другу по подписи
// (signature-разрешение os.z7.permission.CONTROL), а обновления ставятся поверх.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "os.z7.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "os.z7.launcher"
        minSdk = 28
        targetSdk = 28
        versionCode = 6
        versionName = "0.3.1"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
