dependencies {
    testImplementation(project(":spring:boot4-webflux-autoconfigure"))
    testImplementation(libs.jackson.kotlin)
    testImplementation(libs.kotlin.coroutines.reactor)
    testImplementation(libs.spring.boot4.starter.test)
    testImplementation(libs.spring.boot4.health)
}
