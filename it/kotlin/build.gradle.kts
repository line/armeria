// This module is for testing that Armeria doesn't start when annotated services have Kotlin suspending functions
// and the `:armeria-kotlin` dependency is not added.
dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
