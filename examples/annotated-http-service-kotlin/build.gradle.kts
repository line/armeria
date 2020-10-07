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

plugins {
    application
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint") version "9.2.1"
}

dependencies {
    implementation(project(":kotlin"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application {
    mainClassName = "example.armeria.server.annotated.kotlin.MainKt"
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf(
            "-Xjsr305=strict",
            "-java-parameters"
        )
    }
}
