dependencies {
    api(libs.kubernetes.client.api)
    api(libs.kubernetes.client.impl)
    testImplementation(variantOf(libs.kubernetes.client.api) { classifier("tests") })
    testImplementation(libs.kubernetes.server.mock)
    testImplementation(libs.kubernetes.junit.jupiter)
}
