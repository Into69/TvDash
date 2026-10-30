plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The APK is published, so it holds no keys: tools/tv.ps1 install sends the ones in local.properties
// (wu.*, unifi.*) to each TV.

/**
 * The build number is the commit count, the same locally and in CI, so a TV can tell whether a
 * GitHub release is newer than what it runs. 1 outside a git checkout.
 */
val buildNumber: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "dev.tvdash.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tvdash.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "build $buildNumber"
        // GitHub "owner/repo" whose releases the Updates tile installs; empty hides that option.
        buildConfigField("String", "UPDATE_REPO", "\"${providers.gradleProperty("tvdash.updateRepo").getOrElse("")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Live camera video (Protect's RTSP streams) in the screensaver and fullscreen view.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
