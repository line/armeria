dependencies {
    implementation(project(":kotlin"))
    implementation(project(":grpc"))

    implementation(libs.grpc.kotlin)
    implementation(libs.kotlin.coroutines.jdk8)
}
