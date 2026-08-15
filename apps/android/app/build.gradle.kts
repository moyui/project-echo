plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.echo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echo.android"
        minSdk = 26
        targetSdk = 35
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // ML Kit Task 的 await() 在 OcrEngine.kt 里手写扩展
    //（kotlinx-coroutines-play-services 官方已停更，不再引入）

    // ML Kit 端侧 OCR（bundled 版本，不依赖 Google Play 服务，国产 ROM 可用）
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // UniFFI Kotlin 绑定依赖 JNA
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // ONNX Runtime：PP-OCRv5 rec（默认引擎，打包进 assets）+ manga-ocr（按需下载）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
