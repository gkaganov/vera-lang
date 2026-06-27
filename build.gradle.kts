import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

allprojects {
    group = "org.vera-lang"
    version = "0.0.1-SNAPSHOT"
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.gradle.versions.check) apply false
    alias(libs.plugins.antlr.kotlin) apply false
}

val jvmVersion = libs.versions.jvm.get().toInt()
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.github.ben-manes.versions")

    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(jvmVersion) }
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(jvmVersion)) }
}
