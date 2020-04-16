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
}

apply(plugin = "org.jlleitschuh.gradle.ktlint")

application {
    mainClassName = "example.armeria.grpc.kotlin.MainKt"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":grpc"))
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    compileOnly("jakarta.annotation:jakarta.annotation-api")
    runtimeOnly("org.slf4j:slf4j-simple")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("kotlin-reflect"))
    implementation(kotlin("kotlin-stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.grpc:grpc-kotlin-stub")

    testImplementation("jakarta.annotation:jakarta.annotation-api")
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
