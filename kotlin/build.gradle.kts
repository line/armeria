dependencies {
    implementation(project(":core"))
    // Added for supporting Kotlin types in Jackson{Request,Response}ConverterFunction
    implementation(libs.jackson.kotlin)

    // kotlin
    implementation(libs.kotlin.coroutines.jdk8)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.reactivestreams.tck)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
}

testing {
    suites {
        @Suppress("UNUSED_VARIABLE")
        val testNg by registering(JvmTestSuite::class) {
            useTestNG()

            targets {
                all {
                    testTask.configure {
                        group = "Verification"
                        description = "Runs the TestNG unit tests"
                    }
                }
            }
        }
    }
}

tasks.shadedTest { finalizedBy(testing.suites.named("testNg")) }
tasks.check { dependsOn(testing.suites.named("testNg")) }
