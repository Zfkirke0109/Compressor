plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val stableDebugKeystore = rootProject.file("ci-debug.keystore")

// Best-effort short git commit for build provenance in structured diagnostics. Never fails the
// build (shallow CI checkouts or a missing git binary fall back to "unknown").
val buildGitCommit: String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (process.exitValue() == 0 && text.isNotEmpty()) text else "unknown"
} catch (_: Exception) {
    "unknown"
}

android {
    namespace = "compress.joshattic.us"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.zfkirke0109.galaxycompressor"
        minSdk = 24
        targetSdk = 36
        versionCode = 26
        versionName = "1.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_COMMIT", "\"$buildGitCommit\"")
    }

    signingConfigs {
        getByName("debug") {
            if (stableDebugKeystore.isFile) {
                storeFile = stableDebugKeystore
                storePassword = System.getenv("COMPRESSOR_DEBUG_KEYSTORE_PASSWORD") ?: "compressor-debug"
                keyAlias = System.getenv("COMPRESSOR_DEBUG_KEY_ALIAS") ?: "compressor-debug"
                keyPassword = System.getenv("COMPRESSOR_DEBUG_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInBundle = false
        includeInApk = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.exifinterface)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
