dependencies {
    implementation(project(":core"))
    // Added for supporting Kotlin types in Jackson{Request,Response}ConverterFunction
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.reactivestreams:reactive-streams-tck")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
    }
}

val testNg by tasks.registering(Test::class) {
    group = "Verification"
    description = "Runs the TestNG unit tests"
    useTestNG()
}

tasks.shadedTest { finalizedBy(testNg) }
tasks.check { dependsOn(testNg) }
