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

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    // https://github.com/pinterest/ktlint/issues/764
    version.set("0.36.0")
    verbose.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
    }
    // https://github.com/pinterest/ktlint/issues/527
    disabledRules.set(setOf("import-ordering"))
}

dependencies {
    implementation(project(":core"))

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf(
            "-Xjsr305=strict",
            "-java-parameters"
        )
    }
}
