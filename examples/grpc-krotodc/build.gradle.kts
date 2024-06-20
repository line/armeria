plugins {
    application
}

application {
    mainClass.set("example.armeria.grpc.krotodc.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":grpc"))
    compileOnly(libs.javax.annotation)
    runtimeOnly(libs.slf4j.simple)

    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.grpc.kotlin)
    implementation(libs.protobuf.java.util)

    testImplementation(project(":junit5"))
    testImplementation(libs.javax.annotation)
    testImplementation(libs.json.unit.fluent)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testImplementation(libs.junit5.jupiter.api)

    implementation(libs.grpc.krotodc.core)
}
