dependencies {
    implementation(project(":kotlin"))
    implementation(project(":grpc"))

    // kotlin
    implementation(libs.kotlin.coroutines.jdk8)

    testImplementation(libs.kotlin.coroutines.test)
}
