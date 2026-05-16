dependencies {
    testImplementation(project(":jvm-lowering"))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
