plugins {
    application
}

dependencies {
    implementation(project(":graphql"))
    implementation(project(":kotlin"))
    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.coroutines.jdk8)
    implementation(libs.graphql.kotlin.client)
    implementation(libs.graphql.kotlin.client.jackson)
    implementation(libs.graphql.kotlin.client.serialization)
    implementation(libs.graphql.kotlin.schema.generator)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(project(":junit5"))
    testImplementation(libs.assertj)
    testImplementation(libs.junit5.jupiter.api)
}

application {
    mainClass.set("example.armeria.server.graphql.kotlin.MainKt")
}
