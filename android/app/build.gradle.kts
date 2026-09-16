plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.baziche.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.baziche.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.2.0"
        // Debug default = local backend via emulator. Override with -PBAZICHE_API_URL=...
        val apiUrl = (project.findProperty("BAZICHE_API_URL") as String?)?.trim()?.ifEmpty { null }
            ?: "http://10.0.2.2:8787/api/v1/"
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
    }
    buildTypes {
        debug {
            // uses defaultConfig value above
        }
        release {
            isMinifyEnabled = false
            // Release MUST be configured during Cloudflare setup (docs/CLOUDFLARE_SETUP.md).
            // Placeholder below is replaced by -PBAZICHE_API_URL; never ship the placeholder.
            val apiUrl = (project.findProperty("BAZICHE_API_URL") as String?)?.trim()?.ifEmpty { null }
                ?: "https://api.baziche.example.com/api/v1/"
            buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":editor"))
    implementation(project(":preview"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.coroutines.bom))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.junit4)
}
