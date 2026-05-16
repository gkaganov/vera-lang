import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask

val generatedAntlrRoot: Provider<Directory> = layout.buildDirectory.dir("generated/sources/antlr/main/kotlin")
val antlrPackage = "vera.antlr"

plugins {
    alias(libs.plugins.antlr.kotlin)
}

dependencies {
    implementation(libs.antlr.kotlin.runtime)
    implementation(libs.arrow.core)

    api(project(":ast"))
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generatedAntlrRoot)
    }
}

val generateKotlinGrammarSource = tasks.register<AntlrKotlinTask>("generateKotlinGrammarSource") {
    source = fileTree(layout.projectDirectory.dir("src/main/antlr")) {
        include("**/*.g4")
    }

    packageName = antlrPackage
    arguments = listOf("-no-listener")

    outputDirectory = generatedAntlrRoot
        .map { it.dir(antlrPackage.replace('.', '/')) }
        .get()
        .asFile
}

tasks.named("compileKotlin") {
    dependsOn(generateKotlinGrammarSource)
}
