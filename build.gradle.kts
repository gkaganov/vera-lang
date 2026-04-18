import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.3.20"
    id("antlr")
}

group = "org.greg"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val javaVersion = 25
val antlrVersion = "4.13.2"
val junitVersion = "6.0.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

kotlin {
    jvmToolchain(javaVersion)
}

dependencies {
    implementation("org.antlr:antlr4-runtime:$antlrVersion")
    antlr("org.antlr:antlr4:$antlrVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(kotlin("test"))
}

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        kotlin.srcDir(layout.buildDirectory.dir("generated-src/antlr/main"))
    }
    test {
        kotlin.srcDir("src/test/kotlin")
    }
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-no-listener")
    outputDirectory = layout.buildDirectory.dir("generated-src/antlr/main/org/greg/antlr4").get().asFile
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.test {
    useJUnitPlatform()
}
