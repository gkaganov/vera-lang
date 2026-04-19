rootProject.name = "vera-lang"

pluginManagement {
    val kotlinVersion: String by settings
    val antlrKotlinVersion: String by settings

    plugins {
        kotlin("jvm") version kotlinVersion
        id("com.strumenta.antlr-kotlin") version antlrKotlinVersion
    }
}
