dependencies {
    testImplementation(project(":compiler"))

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
