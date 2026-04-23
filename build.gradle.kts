import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask

group = "org.greg"
version = "1.0-SNAPSHOT"

val junitVersion: String by project
val jvmVersion: String by project
val antlrKotlinVersion: String by project
val arrowVersion: String by project

val antlrPackage = "${project.group}.antlr"
val generatedAntlrRoot: Provider<Directory> = layout.buildDirectory.dir("generated/sources/antlr/main/kotlin")

repositories.mavenCentral()
java.toolchain.languageVersion.set(JavaLanguageVersion.of(jvmVersion))
kotlin.jvmToolchain(jvmVersion.toInt())

plugins {
    kotlin("jvm")
    id("com.strumenta.antlr-kotlin")
}

dependencies {
    implementation("com.strumenta:antlr-kotlin-runtime:$antlrKotlinVersion")
    implementation("io.arrow-kt:arrow-core:$arrowVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(kotlin("test"))
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

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generatedAntlrRoot)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateKotlinGrammarSource)
}

tasks.test.configure {
    useJUnitPlatform()
}
