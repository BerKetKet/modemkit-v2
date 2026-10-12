plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.minebutt.modemkit"
    compileSdk = 34
    defaultConfig { applicationId = "com.minebutt.modemkit"; minSdk = 26; targetSdk = 28; versionCode = 3; versionName = "3.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { compileOnly("de.robv.android.xposed:api:82") }
