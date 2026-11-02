dependencies {
    api(project(":shared-model"))

    implementation(project(":parser"))
    implementation(project(":jvm-lowering"))
    implementation(project(":jvm-ir"))
    implementation(project(":jvm-bytecode-emitter"))
}
