plugins {
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    // required if you want to use preprocessors
    kotlin("kapt") apply true
}

dependencies {
    implementation(project(":spring:boot3-starter"))
    implementation(libs.hibernate.validator8)

    implementation(project(":spring:boot3-actuator-starter"))

    implementation(libs.jackson.kotlin)
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))

    testImplementation(libs.json.unit.fluent)
    testImplementation(libs.junit5.jupiter.api)
    testImplementation(libs.spring.boot3.starter.test)

    // Preprocessor that enables you to use KDoc to add description to REST API parameters.
    // If you don't want to use it, you can use the annotation
    // com.linecorp.armeria.server.annotation.Description otherwise.
    configurations["kapt"].dependencies.add(project(":annotation-processor"))
}

kapt {
    annotationProcessor("com.linecorp.armeria.server.annotation.processor.DocumentationProcessor")
}
