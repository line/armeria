// Guards the Kotlin compatibility contract of the published Kotlin modules. `:kotlin` and
// `:grpc-kotlin` pin their Kotlin language version and their kotlin-stdlib to `kotlin-baseline`, so
// that a user on an older Kotlin can upgrade Armeria without upgrading Kotlin.
dependencies {
    implementation(project(":kotlin"))
    implementation(project(":grpc-kotlin"))
}

// Resolves the two modules the way a user's Kotlin compile classpath does, as jars rather than as the
// class directories a project dependency resolves to inside this build.
val kotlinConsumerClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                  objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    }
}

tasks.withType<Test> {
    val baseline = libs.versions.kotlin.baseline.get()
    inputs.files(kotlinConsumerClasspath)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Darmeria.kotlin.baseline=$baseline",
               "-Darmeria.kotlin.compileClasspath=${kotlinConsumerClasspath.asPath}")
    })
}
