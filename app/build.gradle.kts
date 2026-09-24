plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release builds get their version from the tag (see .github/workflows/release.yml).
val appVersion: String = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "1.1.0"
/** 1.2.3 -> 10203, so every release installs over the previous one. */
val appVersionCode: Int = appVersion.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { p -> p.getOrElse(0) { 0 } * 10000 + p.getOrElse(1) { 0 } * 100 + p.getOrElse(2) { 0 } }
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.local.joyamp"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.local.joyamp"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // No release key (local build, fork): fall back to the debug key so the APK still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
