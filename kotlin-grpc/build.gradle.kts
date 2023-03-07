dependencies {
    implementation(project(":kotlin"))
    implementation(project(":grpc"))
    implementation(project(":junit5"))

    // kotlin
    implementation(libs.kotlin.coroutines.jdk8)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.reactivestreams.tck)
    testImplementation(libs.kotlin.coroutines.test)
}
