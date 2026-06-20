dependencies {
    api(project(":shared-model"))

    implementation(project(":antlr-parser"))
    implementation(project(":jvm-lowering"))
    implementation(project(":jvm-ir"))
    implementation(project(":jvm-bytecode-emitter"))
}
