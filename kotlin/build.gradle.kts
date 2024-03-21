dependencies {
    implementation(project(":core"))
    // Added for supporting Kotlin types in Jackson{Request,Response}ConverterFunction
    implementation(libs.jackson.kotlin)

    // kotlin
    implementation(libs.kotlin.coroutines.jdk8)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.reactivestreams.tck)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
}
