import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    val managedVersions = extra["managedVersions"] as Map<*, *>
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-allopen:${managedVersions["org.jetbrains.kotlin:kotlin-allopen"]}")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${managedVersions["org.jetbrains.kotlin:kotlin-gradle-plugin"]}")
        classpath("org.jlleitschuh.gradle:ktlint-gradle:${managedVersions["org.jlleitschuh.gradle:ktlint-gradle"]}")
        classpath("org.springframework.boot:spring-boot-gradle-plugin:${managedVersions["org.springframework.boot:spring-boot-gradle-plugin"]}")
    }
}

apply(plugin = "org.jetbrains.kotlin.jvm")
apply(plugin = "org.jetbrains.kotlin.plugin.spring")
apply(plugin = "org.jlleitschuh.gradle.ktlint")
apply(plugin = "org.springframework.boot")

dependencies {
    implementation(project(":spring:boot-starter"))
    implementation("org.hibernate.validator:hibernate-validator")

    implementation(project(":spring:boot-actuator-starter"))

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation("net.javacrumbs.json-unit:json-unit-fluent")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "1.8"
    }
}