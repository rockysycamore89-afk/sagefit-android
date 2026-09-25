plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.sagefit.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.sagefit.app"
        minSdk = 26                 // Health Connect needs Android 8.0+
        targetSdk = 36                // Google Play requires Android 16 (API 36) for new apps
        // The Google Play build sets VERSION_CODE so every upload gets a higher number.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0"
    }
    // One fixed test key, so each new GitHub build installs as an UPDATE over the old one
    // (keeps your steps). This is NOT your Google Play key - keep that one off GitHub.
    signingConfigs {
        getByName("debug") {
            storeFile = file("sagefit-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Google Play upload key. It is NOT stored in the repository: the "Build SageFit for
        // Google Play" workflow reads it from the repository's secrets (see PLAY-STORE-GUIDE.txt).
        create("upload") {
            System.getenv("UPLOAD_KEYSTORE_FILE")?.let { path ->
                storeFile = file(path)
                storeType = "pkcs12"
                storePassword = System.getenv("UPLOAD_KEY_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            if (System.getenv("UPLOAD_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")      // wakes up every 15 minutes to save the step count
}
