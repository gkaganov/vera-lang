import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

allprojects {
    group = "org.vera-lang"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(libs.versions.jvm.get().toInt())
        }
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(libs.versions.jvm.get().toInt()))
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.antlr.kotlin) apply false
}

