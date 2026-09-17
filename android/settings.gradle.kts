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
include(":core:billing")
include(":editor")
include(":runtime")
include(":preview")
include(":game-shell")
include(":scaffolder")
// Phase 8+: :iap-core, :iap-myket, ...
