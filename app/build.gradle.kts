plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.tishe.audio"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.tishe.audio"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3:1.5.0-alpha15")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
