plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.baziche.core.network"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(project(":core:common"))
    implementation(platform(libs.coroutines.bom))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.serialization)
    api(libs.okhttp) // multipart upload types are part of BazicheApi's public signature
    implementation(libs.okhttp.logging)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
}
