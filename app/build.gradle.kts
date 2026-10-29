import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.odbol.tonepolicer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.odbol.tonepolicer"
        // ML Kit GenAI APIs require API 26+. Gemini Nano itself only runs on
        // AICore-capable devices (Android 14+, e.g. Pixel 9+, Galaxy S25+).
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // On-device Gemini Nano through AICore (ML Kit GenAI Prompt API).
    implementation("com.google.mlkit:genai-prompt:1.0.0-alpha1")
}
