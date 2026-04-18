rootProject.name = "vera-lang"

pluginManagement {
    val kotlinVersion: String by settings
    plugins.kotlin("jvm") version kotlinVersion
}
