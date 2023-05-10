plugins {
    application
}

application {
    mainClass.set("example.armeria.grpc.kotlin.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":grpc"))
    implementation(libs.reactor.kotlin)
    compileOnly(libs.javax.annotation)
    runtimeOnly(libs.slf4j.simple)

    implementation(libs.jackson.kotlin)
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.grpc.kotlin)

    testImplementation(project(":junit5"))
    testImplementation(libs.javax.annotation)
    testImplementation(libs.json.unit.fluent)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testImplementation(libs.junit5.jupiter.api)
    testImplementation(libs.protobuf.java.util)
}
