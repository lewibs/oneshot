plugins {
    id("com.android.application")
}

android {
    namespace = "dev.singlepage.viewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.singlepage.viewer"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}
