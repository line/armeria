plugins {
    application
}

dependencies {
    implementation(project(":kotlin"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation(project(":junit5"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}

application {
    mainClass.set("example.armeria.server.annotated.kotlin.MainKt")
}
