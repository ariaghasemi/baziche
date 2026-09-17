plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.baziche.core.billing"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core:common"))
    implementation(platform(libs.coroutines.bom))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
}
