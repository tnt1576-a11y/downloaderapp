plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jonbo.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jonbo.downloader"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // yt-dlp/ffmpeg ship native payloads; limiting ABIs keeps the APK sane.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // The Android emulator on a PC is x86_64. AGP unions this with defaultConfig,
            // so debug covers phone + emulator while release stays phone-only.
            ndk { abiFilters += "x86_64" }
        }
        release {
            // Jackson (used by the yt-dlp JSON mapper) is reflection-heavy; no shrinking
            // keeps this personal build trouble-free.
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Required: the library extracts its python/ffmpeg payload from the APK at runtime.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // The yt-dlp wrapper still declares jackson 2.11.1 (2020) and commons-io 2.5 (2016).
    // Neither is reachable with untrusted input the way we use it, but there is no reason to
    // ship years-old parsers: these constraints force the whole graph up to current releases.
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")
        implementation("com.fasterxml.jackson.core:jackson-annotations:2.19.4")
        implementation("commons-io:commons-io:2.22.0")
    }

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Bundles yt-dlp + python + ffmpeg. This pin IS the yt-dlp version: the app never updates
    // the engine at runtime, so bump these (together) and rebuild when a site stops working.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
