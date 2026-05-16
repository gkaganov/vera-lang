@file:Suppress("UnstableApiUsage")

rootProject.name = "vera-lang"

include("cli")
include("compiler")

include("model")
include("antlr-parser")
include("ast")
include("jvm-lowering")
include("jvm-ir")
include("jvm-bytecode-emitter")

include("compiler-tests")

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
