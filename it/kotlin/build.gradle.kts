// This module is for testing that Armeria doesn't start when annotated services have Kotlin suspending functions
// and the `:armeira-kotlin` dependency is not added.
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
