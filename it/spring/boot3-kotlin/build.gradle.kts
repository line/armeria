dependencies {
    testImplementation(project(":spring:boot3-webflux-autoconfigure"))
    testImplementation(libs.jackson.kotlin)
    testImplementation(libs.kotlin.coroutines.reactor)
    testImplementation(libs.spring.boot3.starter.test)
}
