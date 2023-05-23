dependencies {
    implementation(project(":kotlin"))
    implementation(project(":grpc"))

    implementation(libs.grpc.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.coroutines.jdk8)

    testImplementation(libs.kotlin.coroutines.test)
}
