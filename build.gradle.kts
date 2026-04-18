group = "org.greg"
version = "1.0-SNAPSHOT"

val antlrVersion: String by project
val junitVersion: String by project
val jvmVersion: String by project

repositories.mavenCentral()
java.toolchain.languageVersion.set(JavaLanguageVersion.of(jvmVersion))
kotlin.jvmToolchain(jvmVersion.toInt())

plugins {
    kotlin("jvm")
    id("antlr")
}

dependencies {
    implementation("org.antlr:antlr4-runtime:$antlrVersion")
    antlr("org.antlr:antlr4:$antlrVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(kotlin("test"))
}

tasks.compileKotlin.configure { dependsOn(tasks.generateGrammarSource) }

tasks.generateGrammarSource {
    packageName = "${project.group}.antlr"
    arguments = arguments + listOf("-no-listener")
    outputDirectory = layout.buildDirectory.dir("generated/sources/antlr/main").get().asFile
}

tasks.test.configure { useJUnitPlatform() }
