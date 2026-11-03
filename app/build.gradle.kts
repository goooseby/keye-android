plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.goooseby.keye"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.goooseby.keye"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("keyeVersionCode").orElse("3").get().toInt()
        versionName = providers.gradleProperty("keyeVersionName").orElse("0.1.0").get()
    }

    val releaseStorePath = providers.environmentVariable("KEYE_KEYSTORE_PATH").orNull
    if (!releaseStorePath.isNullOrBlank()) {
        signingConfigs {
            create("publication") {
                storeFile = file(releaseStorePath)
                storePassword = providers.environmentVariable("KEYE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KEYE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KEYE_KEY_PASSWORD").get()
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("publication")
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    testImplementation("junit:junit:4.13.2")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
