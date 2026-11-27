import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

allprojects {
    group = "org.vera-lang"
    version = "0.0.1-SNAPSHOT"
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

val jvmVersion = libs.versions.jvm.get().toInt()
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(jvmVersion) }
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(jvmVersion)) }
}

// Test JVMs are short-lived: the quick C1 compiler and the serial collector start faster than the
// defaults and are fast enough for a suite that runs for a few seconds.
subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")
    }
}
