buildscript {
    val managedVersions = extra["managedVersions"] as Map<*, *>
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-allopen:${managedVersions["org.jetbrains.kotlin:kotlin-allopen"]}")
        classpath("org.springframework.boot:spring-boot-gradle-plugin:${managedVersions["org.springframework.boot:spring-boot-gradle-plugin"]}")
    }
}

plugins {
    // required if you want to use preprocessors
    kotlin("kapt") apply true
}

apply(plugin = "org.jetbrains.kotlin.plugin.spring")
apply(plugin = "org.jlleitschuh.gradle.ktlint")
apply(plugin = "org.springframework.boot")

dependencies {
    implementation(project(":spring:boot2-starter"))
    implementation("org.hibernate.validator:hibernate-validator")

    implementation(project(":spring:boot2-actuator-starter"))

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation("net.javacrumbs.json-unit:json-unit-fluent")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Preprocessor that enables you to use KDoc to add description to REST API parameters.
    // If you don't want to use it, you can use the annotation
    // com.linecorp.armeria.server.annotation.Description otherwise.
    configurations["kapt"].dependencies.add(project(":annotation-processor"))
}

kapt {
    annotationProcessor("com.linecorp.armeria.server.annotation.processor.DocumentationProcessor")
}
