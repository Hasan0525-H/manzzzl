plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.manzzzl.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manzzzl.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3:1.3.0")
}
