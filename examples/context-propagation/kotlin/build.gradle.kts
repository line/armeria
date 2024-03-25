plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":kotlin"))
    runtimeOnly(libs.slf4j.simple)

    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.coroutines.jdk8)
}

application {
    mainClass.set("example.armeria.contextpropagation.kotlin.MainKt")
}
