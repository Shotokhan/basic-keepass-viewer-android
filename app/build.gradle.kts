plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt") // for Room annotation processor
}

android {
    namespace = "com.example.keepassviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.keepassviewer"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3" // adjust to your Compose version
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle / Activity
    implementation("androidx.activity:activity-compose:1.9.2")

    // Room (database for import history)
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // OkHttp (download DB from URL)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // KeePass library (KDBX)
    implementation("org.linguafranca.pwdb:KeePassJava2:2.1.4")

    // Clipboard helper (optional, built-in Android APIs can be used too)

    // Solve guava duplicate class error
    implementation("com.google.guava:guava:27.0.1-android")
}

