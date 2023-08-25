dependencies {
    api(libs.kubernetes.client.api)
    testImplementation(variantOf(libs.kubernetes.client.api) { classifier("tests") })
    testImplementation(libs.kubernetes.server.mock)
    // TODO(ikhoon): Upgrade mockserver version to 0.3.0. Currently we need to manually build the mockwebserver
    //               dependency because it's not allow OPTIONS and CONNECT methods.
    //               See https://github.com/fabric8io/mockwebserver/pull/79
    testImplementation("io.fabric8:mockwebserver:0.3-SNAPSHOT")
}
