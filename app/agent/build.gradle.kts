plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "os.z7.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "os.z7.agent"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
