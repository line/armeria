import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.fasterxml.jackson.databind.node.ObjectNode
import groovy.lang.Closure
import java.nio.file.Files
import java.nio.file.Path

buildscript {
    dependencies {
        // TODO(trustin): Use platform(libs.boms.jackson) once Gradle supports platform() for build dependencies.
        //                https://github.com/gradle/gradle/issues/21788
        val jacksonDatabind = libs.jackson.databind.get()
        classpath(
            group = jacksonDatabind.group!!,
            name = jacksonDatabind.name,
            version = libs.boms.jackson.get().version
        )
    }
}

plugins {
    base
    `jvm-toolchains`
}

// If `-Pscratch` is specified, do not source the previously generated config at core/src/main/resources/META-INF/native-image
// otherwise, the previously generated config will be merged into the newly generated config.
val shouldGenerateFromScratch = project.findProperty("scratch").let {
    if (it == null) {
        false
    } else if (it == "") {
        true
    } else {
        throw IllegalArgumentException("-Pscratch option must be specified without any value.")
    }
}

@Suppress("UNCHECKED_CAST")
val projectsWithFlags = rootProject.ext["projectsWithFlags"] as Closure<Iterable<Project>>
val relocatedProjects: Iterable<Project> =
    projectsWithFlags.call("java", "relocate", "native")
    //listOf(project(":core")) // Uncomment this for quick testing

val graalLauncher = rootProject.ext["graalLauncher"] as JavaLauncher
val graalHome = rootProject.ext["graalHome"] as Path
val nativeImageConfigToolPath = "${graalHome.resolve("lib/svm/bin/native-image-configure")}"

val thisProject = project
val callerFilterFile = projectDir.resolve("src/trace-filters/caller-filter.json")
val processNativeImageTracesOutputDir = buildDir.resolve("step-1-process-native-image-traces")
val simplifyNativeImageConfigOutputDir = buildDir.resolve("step-2-simplify-native-image-config")
val nativeImageConfigOutputDir = buildDir.resolve("step-3-final-native-image-config")
val nativeImageTraceFiles = mutableListOf<Path>()

tasks.named("clean").configure {
    doFirst {
        delete(projectDir.resolve("gen-src"))
    }
}

tasks.register("processNativeImageTraces", Exec::class).configure {
    group = "Build"
    description = "Generates a native image config from all collected native image traces."

    inputs.file(callerFilterFile)
    inputs.dir(project.file("gen-src/traces"))
    outputs.dir(processNativeImageTracesOutputDir)

    doFirst {
        val newCommandLine = mutableListOf<String>()
        newCommandLine += nativeImageConfigToolPath
        newCommandLine += "generate"
        nativeImageTraceFiles.forEach {
            if (Files.exists(it)) {
                newCommandLine += "--trace-input=$it"
            }
        }
        newCommandLine += "--output-dir=$processNativeImageTracesOutputDir"
        newCommandLine += "--caller-filter-file=$callerFilterFile"
        commandLine = newCommandLine

        // Delete the output directory because otherwise the tool doesn't overwrite the files.
        delete(processNativeImageTracesOutputDir)
    }
}
val processNativeImageTracesTask = tasks.named("processNativeImageTraces", Exec::class)

tasks.register("simplifyNativeImageConfig", SimplifyNativeImageConfigTask::class).configure {
    dependsOn(processNativeImageTracesTask)
    group = "Build"
    description = "Simplifies up the generated native image config."

    sourceNativeImageConfigDir.set(processNativeImageTracesOutputDir)
    targetNativeImageConfigDir.set(simplifyNativeImageConfigOutputDir)
    baseResourceConfigFile.set(project.file("src/base-config/resource-config.json"))

    excludedTypeRegexes.apply {
        // Exclude the classes that are referenced only from the tests.
        add("""Test(?:Utils?)?(?:$|\$)""".toRegex())
        add("""Suite(?:$|\$)""".toRegex())
        add("""\.Test[A-Z]""".toRegex())
        add("""^android\.""".toRegex())
        add("""^brave\.test\.""".toRegex())
        add("""^com\.gradle\.""".toRegex())
        add("""^com\.sun\.jna\.""".toRegex())
        add("""^groovyx?\.""".toRegex())
        add("""^io\.grpc\.netty\.""".toRegex())
        add("""^io\.grpc\.okhttp\.""".toRegex())
        add("""^munit\.""".toRegex())
        add("""^org\.assertj\.""".toRegex())
        add("""^org\.mockito\.""".toRegex())
        add("""^org\.gradle\.""".toRegex())
        add("""^org\.junit\.jupiter\.""".toRegex())
        add("""^org\.reactivestreams\.tck\.""".toRegex())
        add("""^org\.testcontainers\.""".toRegex())
        add("""^org\.testng\.""".toRegex())
        add("""^testing\.""".toRegex())
        add("""^worker\.org\.gradle\.""".toRegex())
    }

    excludedResourceRegexes.apply {
        // Exclude the resource files that are referenced only from the tests.
        add("""Test(?:Utils?)?\.""".toRegex())
        add("""^test(?:ing)?[/\\.]""".toRegex())
        add("""^CatalogManager\.properties$""".toRegex())
        add("""^META-INF/armeria/grpc$""".toRegex())
        add("""^META-INF/dgminfo""".toRegex())
        add("""^META-INF/groovyx?/""".toRegex())
        add("""^META-INF/native/libio_grpc_netty_""".toRegex())
        add("""^META-INF/services/net\.javacrumbs\.jsonunit\.""".toRegex())
        add("""^META-INF/services/org\.apache\.groovy\.""".toRegex())
        add("""^META-INF/services/org\.apache\.xerces\.""".toRegex())
        add("""^META-INF/services/org\.assertj\.""".toRegex())
        add("""^META-INF/services/org\.codehaus\.groovy\.""".toRegex())
        add("""^META-INF/services/org\.junit(?:pioneer)?\.""".toRegex())
        add("""^META-INF/services/org\.testcontainers\.""".toRegex())
        add("""^META-INF/services/org\.testng\.""".toRegex())
        add("""^catalog/""".toRegex())
        add("""^com/sun/jna/""".toRegex())
        add("""^docker-java\.properties$""".toRegex())
        add("""^jndi\.properties$""".toRegex())
        add("""^junit-platform\.properties$""".toRegex())
        add("""^log4testng\.properties$""".toRegex())
        add("""^logback-test\.xml$""".toRegex())
        add("""^mockito-extensions/""".toRegex())
        add("""^mozilla/""".toRegex())
        add("""^org/apache/hc/""".toRegex())
        add("""^org/apache/http/""".toRegex())
        add("""^org/apache/xml/""".toRegex())
        add("""^testcontainers\.properties$""".toRegex())
    }
}
val simplifyNativeImageConfigTask = tasks.named("simplifyNativeImageConfig", SimplifyNativeImageConfigTask::class)

tasks.register("nativeImageConfig", Exec::class).configure {
    group = "Build"
    description = "Generates the final native image config by merging the base and generated native image config."

    dependsOn(simplifyNativeImageConfigTask)

    val baseConfigDir = project.file("src/base-config")
    val previousConfigDir = project.file("../core/src/main/resources/META-INF/native-image/com.linecorp.armeria/armeria")
    inputs.dir(baseConfigDir)
    inputs.dir(previousConfigDir)
    inputs.property("shouldGenerateFromScratch", shouldGenerateFromScratch)
    outputs.dir(nativeImageConfigOutputDir)

    val args = mutableListOf<String>()
    args += nativeImageConfigToolPath
    args += "generate"
    args += "--output-dir=$nativeImageConfigOutputDir"
    args += "--input-dir=$baseConfigDir"
    args += "--input-dir=$simplifyNativeImageConfigOutputDir"
    // Do not feed the previously generated config when `-Pscratch` option is specified.
    if (!shouldGenerateFromScratch) {
        args += "--input-dir=$previousConfigDir"
    }

    commandLine(args)

    doFirst {
        // Delete the output directory because otherwise the tool doesn't overwrite the files.
        delete(nativeImageConfigOutputDir)
    }

    doLast {
        // Reformat all JSON files for consistent output.
        // While reformatting, remove the comment patterns such as "$---- ... ----$" from resource-config.json.
        val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()!!
        fun reformat(file: File, filter: (JsonNode) -> JsonNode = { it }) {
            val obj = mapper.readTree(file)
            val filteredObj = filter(obj)
            mapper.writeValue(file, filteredObj)
        }

        nativeImageConfigOutputDir.walk().forEach { file ->
            if (file.isFile && file.name.endsWith(".json")) {
                logger.info("Reformatting $file ..")
                if (file.name != "resource-config.json") {
                    reformat(file)
                } else {
                    reformat(file) { obj ->
                        val resourcesObj = obj["resources"] as ObjectNode
                        val includesList = resourcesObj["includes"] as ArrayNode
                        includesList.removeAll { it["pattern"].asText().startsWith('$') }
                        obj
                    }
                }
            }
        }
    }
}

configure(relocatedProjects) {
    val relocatedProject = this
    processNativeImageTracesTask.configure {
        dependsOn(relocatedProject.tasks["nativeImageTrace"])
        val nativeImageTraceFile = relocatedProject.ext["nativeImageTraceFile"] as Path
        nativeImageTraceFiles.add(nativeImageTraceFile)
    }
}

abstract class SimplifyNativeImageConfigTask : DefaultTask() {

    private val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

    @InputDirectory
    val sourceNativeImageConfigDir: DirectoryProperty = project.objects.directoryProperty()

    @Input
    val excludedTypeRegexes: SetProperty<Regex> = project.objects.setProperty(Regex::class.java)

    @InputFile
    val baseResourceConfigFile: RegularFileProperty = project.objects.fileProperty()

    @Input
    val excludedResourceRegexes: SetProperty<Regex> = project.objects.setProperty(Regex::class.java)

    @OutputDirectory
    val targetNativeImageConfigDir: DirectoryProperty = project.objects.directoryProperty()

    private val sourceDir: Directory
        get() = sourceNativeImageConfigDir.get()

    private val targetDir: Directory
        get() = targetNativeImageConfigDir.get()

    private val _baseResourceConfigFile: RegularFile
        get() = baseResourceConfigFile.get()

    private val _excludedTypeRegexes: Set<Regex>
        get() = if (excludedTypeRegexes.isPresent) {
            excludedTypeRegexes.get()
        } else {
            emptySet()
        }

    private val _excludedResourceRegexes: Set<Regex>
        get() = if (excludedResourceRegexes.isPresent) {
            excludedResourceRegexes.get()
        } else {
            emptySet()
        }

    @TaskAction
    fun generate() {
        project.mkdir(targetNativeImageConfigDir)

        // Copy the unchanged files first.
        project.copy {
            from(sourceDir.file("predefined-classes-config.json"))
            into(targetDir)
        }
        val agentExtractedPredefinedClasses = "agent-extracted-predefined-classes"
        project.mkdir(targetDir.dir(agentExtractedPredefinedClasses))
        project.copy {
            from(sourceDir.dir(agentExtractedPredefinedClasses))
            into(targetDir.dir(agentExtractedPredefinedClasses))
        }

        // Generate the filtered config files.
        copyTypeConfig("jni-config.json")
        copyTypeConfig("reflect-config.json")
        copyTypeConfig("serialization-config.json") { it["types"] }
        copyProxyTypeConfig("proxy-config.json")
        copyResourceConfig("resource-config.json")
    }

    private fun copyTypeConfig(fileName: String, typeArraySelector: (JsonNode) -> JsonNode = { it }) {
        val config = loadJson(fileName)
        filterTypes(fileName, typeArraySelector(config))
        mapper.writeValue(targetDir.file(fileName).asFile, config)
    }

    private fun copyProxyTypeConfig(fileName: String) {
        val config = loadJson(fileName)
        filterProxyTypes(fileName, config)
        mapper.writeValue(targetDir.file(fileName).asFile, config)
    }

    private fun copyResourceConfig(fileName: String) {
        val config = loadJson(fileName)
        val baseConfig = mapper.readTree(_baseResourceConfigFile.asFile)
        val baseResourceRegexes = baseConfig.get("resources").get("includes").map {
            val pattern = it.get("pattern").asText()
            pattern.substringAfter(':', pattern).toRegex()
        }.toList()
        filterResources(fileName, config, baseResourceRegexes)
        mapper.writeValue(targetDir.file(fileName).asFile, config)
    }

    private fun loadJson(fileName: String): JsonNode = mapper.readTree(sourceDir.file(fileName).asFile)

    private fun filterTypes(fileName: String, array: JsonNode) {
        requireArray(fileName, array)
        val iterator = array.iterator()
        while (iterator.hasNext()) {
            val element = requireObject(fileName, iterator.next())
            val typeName = getTypeName(element["name"].asText()) ?: continue
            if (_excludedTypeRegexes.any { it.find(typeName) != null }) {
                logger.info("$fileName: Excluding $typeName")
                iterator.remove()
                continue
            }
        }
    }

    private fun filterProxyTypes(fileName: String, array: JsonNode) {
        requireArray(fileName, array)
        val iterator = array.iterator()
        while (iterator.hasNext()) {
            val element = iterator.next()
            val interfaceList = requireArray(fileName, element["interfaces"])
            val interfaceIterator = interfaceList.iterator()
            while (interfaceIterator.hasNext()) {
                val interfaceElement = interfaceIterator.next()
                val typeName = interfaceElement.asText()
                if (_excludedTypeRegexes.any { it.find(typeName) != null }) {
                    logger.info("$fileName: Excluding $typeName")
                    interfaceIterator.remove()
                }
            }

            if (interfaceList.isEmpty) {
                iterator.remove()
            }
        }
    }

    private val resourceRegex = """^(?:[._a-zA-Z0-9:]*)\\Q(.*)\\E$""".toRegex()

    private fun filterResources(fileName: String, obj: JsonNode, baseResourcePatterns: Iterable<Regex>) {
        requireObject(fileName, obj)
        val resourcesObj = requireObject(fileName, obj["resources"])
        val includesList = requireArray(fileName, resourcesObj["includes"])
        val iterator = includesList.iterator()
        while (iterator.hasNext()) {
            val element = iterator.next()
            resourceRegex.matchEntire(element["pattern"].asText())?.groups?.let {
                val resourcePath = "${it[1]?.value}"
                if (baseResourcePatterns.any { it.matches(resourcePath) }) {
                    // Base config already covers it.
                    logger.info("$fileName: Skipping $resourcePath")
                    iterator.remove()
                } else if (_excludedResourceRegexes.any { it.find(resourcePath) != null }) {
                    logger.info("$fileName: Excluding $resourcePath")
                    iterator.remove()
                }
            }
        }
    }

    private fun getTypeName(maybeTypeName: String): String? = if (maybeTypeName.startsWith('[')) {
        if (maybeTypeName.startsWith("[L") && maybeTypeName.endsWith(';')) {
            // Extract a class name from array type.
            maybeTypeName.substring(2, maybeTypeName.length - 1)
        } else {
            // Not a class type.
            null
        }
    } else {
        maybeTypeName.substringBefore('[', maybeTypeName) // Foo[][] -> Foo
    }

    private fun requireObject(fileName: String, obj: JsonNode): ObjectNode {
        require(obj.isObject) { "Invalid configuration: $fileName (expected ${JsonNodeType.OBJECT} but got ${obj.nodeType})" }
        return obj as ObjectNode
    }

    private fun requireArray(fileName: String, array: JsonNode): ArrayNode {
        require(array.isArray) { "Invalid configuration: $fileName (expected ${JsonNodeType.ARRAY} but got ${array.nodeType})" }
        return array as ArrayNode
    }
}
