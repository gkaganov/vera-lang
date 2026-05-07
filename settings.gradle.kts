@file:Suppress("UnstableApiUsage")

rootProject.name = "vera-lang"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenCentral()
    }
}

include("antlr-parser")
include("ast")
