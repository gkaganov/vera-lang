dependencies {
    api(project(":shared-model"))

    implementation(project(":parser"))
    implementation(project(":jvm-lowering"))
    implementation(project(":jvm-instruction-lowering"))
    implementation(project(":jvm-bytecode-emitter"))
    implementation(project(":jvm-ir"))
}
