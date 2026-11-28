import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(propName: String, envName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propName)?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.cloudwind.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cloudwind.app"
        minSdk = 30 // MediaStore.createDeleteRequest (batch delete with one prompt) needs Android 11
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    val releaseStorePath = signingProp("storeFile", "CLOUDWIND_KEYSTORE_PATH")
    val releaseStorePass = signingProp("storePassword", "CLOUDWIND_KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingProp("keyAlias", "CLOUDWIND_KEY_ALIAS")
    val releaseKeyPass = signingProp("keyPassword", "CLOUDWIND_KEY_PASSWORD")

    signingConfigs {
        if (releaseStorePath != null && releaseStorePass != null && releaseKeyAlias != null && releaseKeyPass != null) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
