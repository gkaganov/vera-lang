@file:Suppress("UnstableApiUsage")

rootProject.name = "vera-lang"

include("cli")
include("compiler")

include("shared-model")
include("antlr-parser")
include("ast")
include("jvm-lowering")
include("jvm-ir")
include("jvm-bytecode-emitter")

include("compiler-tests")

rootProject.children.forEach {
    it.projectDir = file("modules/${it.name}")
}

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
