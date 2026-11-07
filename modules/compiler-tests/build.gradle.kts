dependencies {
    testImplementation(project(":compiler"))
    testImplementation(project(":shared-model"))
    testImplementation(project(":parser"))
    testImplementation(project(":jvm-lowering"))
    testImplementation(project(":jvm-instruction-lowering"))
    testImplementation(project(":jvm-bytecode-emitter"))
    testImplementation(project(":jvm-ir"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
