plugins {
    application
}

application {
    mainClass.set("example.armeria.grpc.kotlin.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":grpc"))
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    compileOnly("javax.annotation:javax.annotation-api")
    runtimeOnly("org.slf4j:slf4j-simple")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.grpc:grpc-kotlin-stub")

    testImplementation(project(":junit5"))
    testImplementation("javax.annotation:javax.annotation-api")
    testImplementation("net.javacrumbs.json-unit:json-unit-fluent")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}
