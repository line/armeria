dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":grpc"))

    testImplementation(kotlin("reflect"))
    testImplementation(kotlin("stdlib-jdk8"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation("io.grpc:grpc-kotlin-stub")
    testImplementation("javax.annotation:javax.annotation-api")
}
