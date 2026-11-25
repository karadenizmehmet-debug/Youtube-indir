plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.karadeniz.indir"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.karadeniz.indir"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        // S25 ve güncel telefonlar arm64; diğer mimarileri almamak APK'yı küçültür
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Sabit imza: her derleme aynı anahtarla imzalanır, güncelleme kaldırmadan kurulur.
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/indir.jks")
            storePassword = "indir123"
            keyAlias = "indir"
            keyPassword = "indir123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    // yt-dlp/python ikili dosyaları nativeLibraryDir'den çalıştırılır: çıkarılmış olmalı
    packaging {
        jniLibs { useLegacyPackaging = true }
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
}
