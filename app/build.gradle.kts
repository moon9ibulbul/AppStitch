plugins {
    id("com.android.application")
    id("com.chaquo.python")
    kotlin("android")
}

android {
    namespace = "com.astral.stitchapp"
    compileSdk = 34

    // (Opsional tapi dianjurkan) Kunci versi NDK agar build CI konsisten
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.astral.stitchapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.0"

        // Wajib untuk Chaquopy: pilih ABI yang mau dibangun
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // Jika perlu dukungan emulator x86, tambahkan:
            // abiFilters += listOf("x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/LICENSE"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

chaquopy {
    defaultConfig {
        // Pakai Python 3.11 di host build supaya paket sumber lama tetap kompatibel.
        buildPython = "python3.11"

        pip {
            // Gunakan repo Chaquopy dan bangun Pillow dengan WebP/JPEG/zlib.
            options("--extra-index-url", "https://chaquo.com/pypi-16.1")
            install(
                "pillow==9.2.0",
                "--global-option=build_ext",
                "--global-option=--enable-webp",
                "--global-option=--enable-jpeg",
                "--global-option=--enable-zlib"
            )

            // Paket lain mengikuti opsi default repository.
            install("numpy<2")
            install("natsort==8.4.0")
            install("opencv-python")
            install("requests")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
