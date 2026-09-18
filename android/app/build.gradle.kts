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
    }

    /*
     * API endpoint configuration
     *
     * Usage:
     *
     * Local emulator:
     *   -PBAZICHE_API_URL=http://10.0.2.2:8787/api/v1/
     *
     * Production / real device:
     *   -PBAZICHE_API_URL=https://baziche-api.ariagh1386-atom-baziche2.workers.dev/api/v1/
     *
     * The CI workflow supplies the production URL explicitly.
     */
    val configuredApiUrl =
        (project.findProperty("BAZICHE_API_URL") as String?)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    buildTypes {
        debug {
            val apiUrl = configuredApiUrl
                ?: "http://10.0.2.2:8787/api/v1/"

            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"$apiUrl\""
            )
        }

        release {
            isMinifyEnabled = false

            /*
             * Release builds must always receive an explicit API URL.
             * This prevents accidentally shipping a placeholder endpoint.
             */
            val apiUrl = configuredApiUrl
                ?: throw GradleException(
                    "BAZICHE_API_URL must be provided for release builds."
                )

            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"$apiUrl\""
            )
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
    implementation(project(":core:billing"))
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
