plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.videosplit.app"
    compileSdk = 36
    defaultConfig { applicationId = "com.videosplit.app"; minSdk = 26; targetSdk = 35; versionCode = (providers.gradleProperty("ciVersionCode").orNull?.toIntOrNull() ?: 1); versionName = (providers.gradleProperty("ciVersionName").orNull ?: "1.0.0") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
}
