plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.skyler.musicpresence"
    compileSdk = 34

    val stableKeystore = System.getenv("ANDROID_KEYSTORE_PATH")?.let { path ->
        if (file(path).exists()) file(path) else null
    }
    val stablePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val stableAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "musicalified"

    signingConfigs {
        if (stableKeystore != null && stablePassword != null) {
            create("stable") {
                storeFile = stableKeystore
                storeType = "PKCS12"
                storePassword = stablePassword
                keyAlias = stableAlias
                keyPassword = stablePassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.skyler.musicpresence"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.8.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Stable CI keystore so users can install updates over the existing app.
            if (stableKeystore != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
