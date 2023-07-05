dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":kotlin"))
    testImplementation(project(":grpc"))

    testImplementation(kotlin("reflect"))
    testImplementation(kotlin("stdlib-jdk8"))
    testImplementation(libs.kotlin.coroutines.core)
    testImplementation(libs.grpc.kotlin)
    testImplementation(libs.javax.annotation)
}
