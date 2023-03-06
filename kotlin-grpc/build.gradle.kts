dependencies {
    implementation(project(":kotlin"))
    implementation(project(":grpc"))

    // kotlin
    implementation(libs.kotlin.coroutines.jdk8)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.reactivestreams.tck)
}
