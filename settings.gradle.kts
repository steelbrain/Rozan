pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rozan"

include(
    ":airplay:core",
    ":airplay:fairplay",
    ":airplay:alac",
    ":airplay:android",
    ":airplay:testing",
    ":ui",
    ":app:launcher",
    ":app:airplay",
    ":tools:devserver",
    ":benchmark",
    ":benchmark-airplay",
)
