plugins {
    id("com.android.application")
}

android {
    namespace = "com.hokbetastudio.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hokbetastudio.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.4-android-preview"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
