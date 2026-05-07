plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.antlr.kotlin)
}

dependencies {
    implementation(libs.arrow.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}
