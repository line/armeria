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
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint") version "9.2.1"
}

application {
    mainClassName = "example.armeria.grpc.kotlin.MainKt"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":grpc"))
    implementation(project(":kotlin"))
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    compileOnly("javax.annotation:javax.annotation-api")
    runtimeOnly("org.slf4j:slf4j-simple")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.grpc:grpc-kotlin-stub")

    testImplementation("javax.annotation:javax.annotation-api")
    testImplementation("net.javacrumbs.json-unit:json-unit-fluent")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict", "-java-parameters")
    }
}
