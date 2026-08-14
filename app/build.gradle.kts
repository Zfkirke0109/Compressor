plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val stableDebugKeystore = rootProject.file("ci-debug.keystore")

// Release signing material, supplied only by the release workflow (decoded from repository
// secrets at build time) or by a local keystore you place yourself. Never committed: both
// filenames are in .gitignore.
//
// Deliberately SEPARATE from the debug keystore. The debug key's password falls back to a
// literal in this file, and a Play upload key cannot be rotated freely once published, so the
// two must never be the same key.
val releaseKeystore = rootProject.file("release.keystore")
val releaseStorePassword: String? = System.getenv("COMPRESSOR_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("COMPRESSOR_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? =
    System.getenv("COMPRESSOR_RELEASE_KEY_PASSWORD") ?: releaseStorePassword

// Sign release builds ONLY when the keystore and every credential are actually present.
// A partial configuration must fall back to an unsigned release rather than half-configuring
// AGP, which fails late with an opaque error. This also keeps `./gradlew assembleRelease`
// working locally and on fork PRs, exactly as it does today.
val releaseSigningReady: Boolean = releaseKeystore.isFile &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

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
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.zfkirke0109.galaxycompressor"
        minSdk = 24
        targetSdk = 36
        versionCode = 26
        versionName = "1.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_COMMIT", "\"$buildGitCommit\"")

        // On-device VMAF is arm64-only (libvmaf NEON build). Other ABIs simply run without
        // pixel scoring: VmafNative.isAvailable is false and every caller falls back to the
        // structural-only pipeline.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Both signature schemes: v1 keeps API 24 installable, v2 is required from API 30.
                enableV1Signing = true
                enableV2Signing = true
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
            // Unsigned when credentials are absent — the pre-existing behavior — so local and
            // fork builds keep working. The release workflow verifies the APK is actually
            // signed afterwards, so a silently-unsigned CI artifact cannot slip through.
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
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
