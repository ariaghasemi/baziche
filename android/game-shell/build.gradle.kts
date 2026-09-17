plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val shellAppId = (project.findProperty("BAZICHE_APP_ID") as String?)?.trim()?.ifEmpty { null } ?: "com.baziche.gameshell"
val shellAppName = (project.findProperty("BAZICHE_APP_NAME") as String?)?.trim()?.ifEmpty { null } ?: "Baziche Game"
val shellVersionCode = (project.findProperty("BAZICHE_VERSION_CODE") as String?)?.toIntOrNull() ?: 1
val shellVersionName = (project.findProperty("BAZICHE_VERSION_NAME") as String?)?.trim()?.ifEmpty { null } ?: "1.0.0"
val shellTargetApi = (project.findProperty("BAZICHE_TARGET_API") as String?)?.toIntOrNull()
    ?: libs.versions.targetSdk.get().toInt()
val shellMinApi = (project.findProperty("BAZICHE_MIN_API") as String?)?.toIntOrNull()
    ?: libs.versions.minSdk.get().toInt()
val shellKeystore = (project.findProperty("BAZICHE_KEYSTORE") as String?)?.trim()?.ifEmpty { null }

android {
    namespace = "com.baziche.gameshell"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = shellAppId
        minSdk = shellMinApi
        targetSdk = shellTargetApi
        versionCode = shellVersionCode
        versionName = shellVersionName
        manifestPlaceholders["appName"] = shellAppName
    }
    if (shellKeystore != null) {
        signingConfigs {
            create("gameRelease") {
                storeFile = file(shellKeystore)
                storePassword = project.findProperty("BAZICHE_STORE_PASS") as String?
                keyAlias = (project.findProperty("BAZICHE_KEY_ALIAS") as String?) ?: "game"
                keyPassword = project.findProperty("BAZICHE_KEY_PASS") as String?
            }
        }
    }
    buildTypes {
        debug {}
        release {
            isMinifyEnabled = false
            if (shellKeystore != null) signingConfig = signingConfigs.getByName("gameRelease")
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":preview"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
}
