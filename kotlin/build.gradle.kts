dependencies {
    implementation(project(":core"))
    // Added for supporting Kotlin types in Jackson{Request,Response}ConverterFunction
    implementation(libs.jackson.kotlin)

    // kotlin
    implementation(libs.kotlin.coroutines.jdk8)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.reactivestreams.tck)
    testRuntimeOnly(libs.kotlin.coroutines.debug)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
}

val testNg by tasks.registering(Test::class) {
    group = "Verification"
    description = "Runs the TestNG unit tests"
    useTestNG()
}

tasks.shadedTest { finalizedBy(testNg) }
tasks.check { dependsOn(testNg) }
