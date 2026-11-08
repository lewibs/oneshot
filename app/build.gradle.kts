plugins {
    id("com.android.application")
}

android {
    namespace = "dev.oneshot.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.oneshot.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
}
