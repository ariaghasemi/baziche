// Pure Kotlin/JVM module — NO Android dependencies.
// Deterministic offline project generator (no network, no AI calls).
plugins {
    // No version: pinned by the root `apply false` (AGP 9 ships Kotlin on the classpath).
    id("org.jetbrains.kotlin.jvm")
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit4)
    // Prove every generated project loads in the real runtime.
    testImplementation(project(":runtime"))
}
