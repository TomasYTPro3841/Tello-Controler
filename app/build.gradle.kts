plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versioning is automatic. versionCode = GITHUB_RUN_NUMBER (always increasing, drives Android updates).
// versionName = TELLO_VERSION_NAME from CI: <telloVersionBase>.<releases already published in that line>,
// i.e. 0.5.0, 0.5.1… Bump `telloVersionBase` in gradle.properties for a new minor/major line.
val versionBase = providers.gradleProperty("telloVersionBase").get()
val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

// Release signing key comes from CI secrets (see AGENTS.md). Without it, release falls back to debug signing.
val releaseKeystore = System.getenv("TELLO_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.miaouss90.tellocontroler"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.miaouss90.tellocontroler"
        minSdk = 26
        targetSdk = 35
        versionCode = ciBuildNumber ?: 1
        versionName = System.getenv("TELLO_VERSION_NAME") ?: "$versionBase.0-dev"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("TELLO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TELLO_KEY_ALIAS")
                keyPassword = System.getenv("TELLO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR codes that trigger safe actions (land, hover, photo, record). Pure Java, no native code.
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM (Android's is stubbed in unit tests).
    testImplementation("org.json:json:20240303")
}
