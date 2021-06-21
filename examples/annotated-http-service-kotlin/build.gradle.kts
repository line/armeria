plugins {
    application
}

dependencies {
    implementation(project(":kotlin"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application {
    mainClass.set("example.armeria.server.annotated.kotlin.MainKt")
}
