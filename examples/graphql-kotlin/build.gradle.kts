plugins {
    application
}

dependencies {
    implementation(project(":graphql"))
    implementation(project(":kotlin"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("com.expediagroup:graphql-kotlin-schema-generator")
    implementation("com.expediagroup:graphql-kotlin-client")
    implementation("com.expediagroup:graphql-kotlin-client-jackson")
    implementation("com.expediagroup:graphql-kotlin-client-serialization")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation(project(":junit5"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}

application {
    mainClass.set("example.armeria.server.graphql.kotlin.MainKt")
}
