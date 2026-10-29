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

rootProject.name = "cauce-android"

include(":app")
include(":core:events")
include(":core:ui")
include(":core:sync")
include(":feature:alarmas")
include(":feature:calendario_google")
