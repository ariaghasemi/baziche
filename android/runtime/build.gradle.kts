// Pure Kotlin/JVM module — NO Android dependencies.
// Runs on JVM unit tests, in :preview, and later in :game-shell.
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
}
