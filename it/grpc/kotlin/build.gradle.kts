import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    val managedVersions = extra["managedVersions"] as Map<*, *>
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jlleitschuh.gradle:ktlint-gradle:${managedVersions["org.jlleitschuh.gradle:ktlint-gradle"]}")
    }
}

apply {
    plugin("org.jlleitschuh.gradle.ktlint")
    plugin("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":grpc"))

    testImplementation(kotlin("reflect"))
    testImplementation(kotlin("stdlib-jdk8"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation("io.grpc:grpc-kotlin-stub")
    testImplementation("javax.annotation:javax.annotation-api")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict", "-java-parameters")
    }
}
