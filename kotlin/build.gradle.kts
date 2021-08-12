dependencies {
    implementation(project(":core"))
    // Added for supporting Kotlin types in Jackson{Request,Response}ConverterFunction
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
