plugins {
    application
}

dependencies {
    implementation(project(":kotlin"))
    implementation(libs.kotlin.coroutines.core)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(project(":junit5"))
    testImplementation(libs.assertj)
    testImplementation(libs.junit5.jupiter.api)
}

application {
    mainClass.set("example.armeria.server.annotated.kotlin.MainKt")
}
