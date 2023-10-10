dependencies {
    implementation(project(":kubernetes"))
    implementation(libs.picocli)
    testImplementation("io.fabric8:kubernetes-junit-jupiter:6.9.0")
}
