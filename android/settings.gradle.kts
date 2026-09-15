pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "baziche"
include(":app")
include(":core:common")
include(":core:network")
include(":core:data")
// Phase 2+: :editor
// Phase 3+: :runtime, :preview
// Phase 5+: :game-shell
// Phase 8+: :iap-core, :iap-myket, ...
