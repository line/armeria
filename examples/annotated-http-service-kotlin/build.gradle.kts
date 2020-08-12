plugins {
    application
    kotlin("jvm")
}

dependencies {
    implementation(project(":kotlin"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application {
    mainClassName = "example.armeria.server.annotated.kotlin.MainKt"
}
