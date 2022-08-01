dependencies {
    testImplementation(project(":spring:boot2-webflux-autoconfigure"))
    testImplementation(libs.jackson.kotlin)
    testImplementation(libs.kotlin.coroutines.reactor)
    testImplementation(libs.spring.boot2.starter.test)
}
