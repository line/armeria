# Sensible multi-project defaults for Gradle

The scripts here provides a simple way to configure a Java project with
sensible defaults. By applying them, you can:

- Manage dependencies using a simple TOML file that is fully compatible with [Gradle version catalogs](https://docs.gradle.org/current/userguide/platforms.html).
- Configure Checkstyle and JaCoCo code coverage.
- Add [Javadoc offline links](https://docs.oracle.com/javase/9/javadoc/javadoc-command.htm#GUID-51213F2C-6E01-4A03-A82A-17428A258A0F) easily.
- Add Google Analytics scripts into Javadoc if `googleAnalyticsId` property exists.
- Generate Maven BOM (Bill of Materials).
- Sign and deploy artifacts to a Maven repository.
- Embedding version properties into a JAR.
- Tag a Git repository with a Gradle task.
- Shade some dependencies into the main JAR and strip unreferenced classes
  so that it does not get too large.

## Table of Contents

<!-- MarkdownTOC -->

- [Setup](#setup)
- [Dependency management](#dependency-management)
    - [Importing Maven BOM \(Bill of Materials\)](#importing-maven-bom-bill-of-materials)
    - [Checking if dependencies are up-to-date](#checking-if-dependencies-are-up-to-date)
- [Built-in properties and functions](#built-in-properties-and-functions)
- [Using flags](#using-flags)
    - [Built-in flags](#built-in-flags)
- [Building Java projects with `java` flag](#building-java-projects-with-java-flag)
- [Overriding JDK version](#overriding-jdk-version)
- [Publishing to Maven repository with `publish` flag](#publishing-to-maven-repository-with-publish-flag)
- [Generating Maven BOM with `bom` flag](#generating-maven-bom-with-bom-flag)
- [Building shaded JARs with `shade` flag](#building-shaded-jars-with-shade-flag)
    - [Trimming a shaded JAR with `trim` flag](#trimming-a-shaded-jar-with-trim-flag)
    - [Shading a multi-module project with `relocate` flag](#shading-a-multi-module-project-with-relocate-flag)
- [Tagging conveniently with `release` task](#tagging-conveniently-with-release-task)

<!-- /MarkdownTOC -->

## Setup

1. Run `gradle wrapper` to set up a new project.

   ```
   $ mkdir myproject
   $ cd myproject
   $ gradle wrapper
   $ ls
   gradle/
   gradlew
   gradlew.bat
   ```
2. Copy everything in this directory into `<project_root>/gradle/scripts`.
   If copied correctly, you should see the following `ls` command output:

   ```
   $ ls gradle/scripts
   lib/
   build-flags.gradle
   settings-flags.gradle
   ```

3. Add `settings.gradle` to apply `settings-flags.gradle`:

   ```groovy
   rootProject.name = 'myproject'

   apply from: "${rootDir}/gradle/scripts/settings-flags.gradle"

   includeWithFlags ':foo', 'java', 'publish'
   includeWithFlags ':bar', 'java'
   ```

   Unlike an ordinary `settings.gradle`, it uses a special directive called
   `includeWithFlags` which allows applying one or more flags to a project.
   Both project `foo` and `bar` have the `java` flag, which denotes a Java
   project. Project `foo` also has the `publish` flag, which means its artifact
   will be published to a Maven repository.

3. Add `build.gradle`:

   ```groovy
   plugins {
       id 'com.google.osdetector' version '1.6.2' apply false
       id 'io.github.gradle-nexus.publish-plugin' version '1.1.0' apply false
   }

   apply from: "${rootDir}/gradle/scripts/build-flags.gradle"
   ```

   Note that you have to apply `build-flags.gradle` *only* to the top-level
   `build.gradle`.

4. Add `gradle.properties` that contains the necessary information to publish
   your artifacts to a Maven repository:

   ```
   group=com.doe.john.myexample
   version=0.0.1-SNAPSHOT
   projectName=My Example
   projectUrl=https://www.example.com/
   projectDescription=My example project
   authorName=John Doe
   authorEmail=john@doe.com
   authorUrl=https://john.doe.com/
   inceptionYear=2018
   licenseName=The Apache License, Version 2.0
   licenseUrl=https://www.apache.org/license/LICENSE-2.0.txt
   scmUrl=https://github.com/john.doe/example
   scmConnection=scm:git:https://github.com/john.doe/example.git
   scmDeveloperConnection=scm:git:ssh://git@github.com/john.doe/example.git
   publishUrlForRelease=https://oss.sonatype.org/service/local/staging/deploy/maven2/
   publishUrlForSnapshot=https://oss.sonatype.org/content/repositories/snapshots/
   publishUsernameProperty=ossrhUsername
   publishPasswordProperty=ossrhPassword
   publishSignatureRequired=true
   googleAnalyticsId=UA-XXXXXXXX
   javaSourceCompatibility=1.8
   javaTargetCompatibility=1.8
   ```

5. That's all. You now have two Java subprojects with sensible defaults.
   In the following sections, you'll learn how to make your project more useful.

## Dependency management

Put your dependency versions into `<project_root>/dependencies.toml` so you don't
need to put the version numbers in `build.gradle`.
The `dependencies.toml` file supports the same syntax as Gradle's [`libs.versions.toml`](https://docs.gradle.org/current/userguide/platforms.html#sub:conventional-dependencies-toml).
In addition, it also supports additional properties such as:
- `boms`: A table to manages a list of BOM dependencies.
- `relocations`: A table or an array of tables to relocate specific dependencies to a new location.
- `exclusions`: A string or an array of strings to exclude specific dependencies from the module.
- `javadocs`: A string or an array of strings to link to external Javadocs.

```toml
# Import 'armeria-bom':
[boms]
armeria = { module = "com.linecorp.armeria:armeria-bom", version = "1.17.0" }

# Simple form:
[libraries]
findbugs = { module = "com.google.code.findbugs:jsr305", version = '3.0.2' }

# Slightly more verbose, but useful when an artifact has more than one property:
[libraries.guava]
module = "com.google.guava:guava"
version = '31.1.1-jre'
exclusions = [
  "com.google.code.findbugs:jsr305",
  "com.google.errorprone:error_prone_annotations",
  "com.google.j2objc:j2objc-annotations",
  "org.codehaus.mojo:animal-sniffer-annotations"]
relocations = [
  { from = "com.google.common", to = "com.linecorp.armeria.internal.shaded.guava" },
  { from = "com.google.thirdparty.publicsuffix", to = "com.linecorp.armeria.internal.shaded.publicsuffix" }]

[versions]
reactor = "3.4.19"

[libraries.reactor-core]
module = "io.projectreactor:reactor-core"
# Reference the version declared in `[versions]`:
version.ref = "reactor"
javadocs = "https://projectreactor.io/docs/core/release/api/"
[libraries.reactor-test]
module = "io.projectreactor:reactor-test"
version.ref = "reactor"
```

Dependencies declared in `dependencies.toml` are exposed to build scripts via an extension corresponding to 
their name. 

```groovy
plugins {
    alias libs.plugins.osdetector apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

apply from: "${rootDir}/gradle/scripts/build-flags.gradle"

// Configure all Java projects.
configure(projectsWithFlags('java')) {
    // Common dependencies
    dependencies {
      compileOnly libs.findbugs
      implementation libs.guava
    }
}

// In case you need to get the version number of an artifact:
println "Guava version: ${libs.guava.get().versionConstraint.requiredVersion}"
// Note that it is not recommended to use `managedVersions` with the module defined multiple times with 
// different aliases. Because if a module is declared with different versions, the version returned by 
// `managedVersions` is determined by how the version catalogs are indexed.
println "Guava version: ${managedVersions['com.google.guava:guava']}"
```

### Importing Maven BOM (Bill of Materials)

At `dependencies.toml`, you can add a special section called `boms` to specify
the list of Maven BOMs to import:

```toml
[boms]
armeria = { module = "com.linecorp.armeria:armeria-bom", version = "1.17.0" }

# A dependency that uses the version defined in 'armeria-bom'.
[libraries.armeria]
module = "com.linecorp.armeria:armeria"
```

### Checking if dependencies are up-to-date

[gradle-versions-plugin](https://github.com/ben-manes/gradle-versions-plugin) is
applied so you can conveniently check if your dependencies are out of date:

```
$ ./gradlew dependencyUpdates
...
The following dependencies have later integration versions:
 - com.google.guava:guava [17.0 -> 30.1.1-jre]
```

## Built-in properties and functions

All projects will get the following extension properties:

- `artifactId` - the artifact ID auto-generated from the project name.

  - e.g. When `rootProject.name` is `foo`:

    - The artifact ID of `:bar` becomes `foo-bar`.
    - The artifact ID of `:bar:qux` becomes `foo-bar-qux`

  - You can override the artifact ID of a certain project via the
    `artifactIdOverrides` extension property:

    ```groovy
    ext {
        // Change the artifactId of project ':bar' from 'foo-bar' to 'fubar'.
        artifactIdOverrides = [
            ':bar': 'fubar'
        ]
    }
    ```

- `copyrightFooter` - the copyright footer HTML fragment generated from
  `inceptionYear`, `authorUrl` and `authorName` in `gradle.properties`
  - e.g. `&copy; Copyright 2015&ndash;2018 <a href="https://john.doe.com/">John Doe</a>. All rights reserved.`
- `gitPath` - the path to the `git` command. `null` if Git is not available.
- `executeGit(...args)` - executes a Git command with the specified arguments
- `hasSourceDirectory(name)` - tells if the project has any source directory that matches `<projectDir>/src/*/<name>`, e.g.

  ```groovy
  if (project.ext.hasSourceDirectory('thrift')) {
      println "${project} contains Thrift source files."
  }
  ```

## Using flags

In `build.gradle`, you can retrieve the flags you specified with
`includeWithFlags` in `settings.gradle`:

```groovy
// Getting the flags of a project:
allprojects {
    println "Project '${project.path}' has flags: ${project.flags}"
}

// Finding the projects which have certain flags:
def javaProjects = projectsWithFlags('java')
def publishedJavaProjects = projectsWithFlags('java', 'publish')

// Configuring all Java projects:
configure(projectsWithFlags('java')) {
    // Checking whether a project has certain set of flags.
    if (project.hasFlags('publish')) {
        assert project.hasFlags('java', 'publish')
        println "A Java project '${project.path}' will be published to a Maven repository."
    }
}

// Running a certain task after all Java projects are evaluated:
afterProjectsWithFlag('java') { Set<Project> projects ->
    println 'All Java projects have been evaluated: ${projects}'
}
afterProjectsWithFlags(['java', 'relocated']) { Set<Project> projects ->
    println 'All Java projects with class relocation have been evaluated: ${projects}'
}
```

If you added the snippet above to `build.gradle`, `./gradlew` will show the
following output:

```
$ ./gradlew
> Configure project :
Project ':' has flags: []
Project ':bar' has flags: [java]
Project ':foo' has flags: [java, publish]
A Java project ':foo' will be published to a Maven repository.
```

Note that a flag can be any arbitrary string; you can define your own flags.

### Built-in flags

Some flags, such as `java`, are used for configuring your projects
automatically:

- `java` - Makes a project build a Java source code
- `publish` - Makes a project publish its artifact to a Maven repository
- `bom` - Makes a project publish Maven BOM based on `dependencies.toml`
- `shade`, `relocate` and `trim` - Makes a Java project produce an additional 'shaded' JAR
- `reactor-grpc`, `rxgrpc` or `kotlin-grpc` - Enables [`reactor-grpc`](https://github.com/salesforce/reactive-grpc/tree/master/reactor), [`rxgrpc`](https://github.com/salesforce/reactive-grpc/tree/master/rx-java) or [`kotlin-grpc`](https://github.com/grpc/grpc-kotlin) support to the project

We will learn what these flags exactly do in the following sections.

## Building Java projects with `java` flag

When a project has a `java` flag:

- The following plugins are applied automatically:

  - `java` plugin
  - `eclipse` plugin
  - `idea` plugin

- The `archivesBaseName` is set from the artifact ID of the project.
- Java source and target compatibility options are set from `gradle.properties`.
- Source and Javadoc JARs are generated when:

  - Explicitly requested as a task
  - Publishing to a Maven repository

- Full exception logging is enabled for tests.
- JUnit platform is enabled for tests if the project depends on `junit-jupiter-engine`.
- Checkstyle validation is enabled using `checkstyle` plugin if Checkstyle
  configuration file exists at `<project_root>/settings/checkstyle/checkstyle.xml`

  - A new task called `lint` is added to all projects with Checkstyle enabled.
    - Consider adding dependency tasks to the `lint` task to do other jobs
      such as static analysis.
  - A special configuration property `checkstyleConfigDir` is set so you can
    access the external files such as `suppressions.xml` from `checkstyle.xml`.
  - You can choose Checkstyle version by specifying it in `dependencies.toml`:
    ```toml
    [libraries]
    checkstyle = { module = "com.puppycrawl.tools:checkstyle", version = "10.3.1" }
    ```
  - Checkstyle can be disabled completely by specifying `-PnoLint` option.

- Test coverage report is enabled using `jacoco` plugin if `-Pcoverage` option
  is specified.

  - You can exclude certain packages from the coverage report using the
    `jacocoExclusions` property:

    ```groovy
    rootProject {
        ext {
            jacocoExclusions = [
                    '/com/example/generated/sources/**',
                    '/com/example/third/party/**'
            ]
        }
    }
    ```

- [Jetty ALPN agent](https://github.com/jetty-project/jetty-alpn-agent) is
  loaded automatically when launching a Java process if you specified it in
  `dependencies.toml`:

  ```toml
  [libraries] 
  jetty-alpn = { module = "org.mortbay.jetty.alpn:jetty-alpn-agent", version = "2.0.10" }
  ```

- The `package-list` files of the Javadocs specified in `dependencies.toml` will
  be downloaded and cached. The downloaded `package-list` files will be used
  when generating Javadocs, e.g. in `dependencies.toml`:

  ```toml
  [libraries.grpc-core]
  module = "io.grpc:grpc-core"
  version = "1.47.0"
  javadocs = [ "https://grpc.io/grpc-java/javadoc/",
               "https://developers.google.com/protocol-buffers/docs/reference/java/" ]
  ```

  If you are in an environment with restricted network access, you can specify
  `-PofflineJavadoc` option to disable the downloads.

- The `.proto` files under `src/*/proto` will be compiled into Java code with
  [protobuf-gradle-plugin](https://github.com/google/protobuf-gradle-plugin).

  - A new task called `generateSources` is added to all projects with `.proto`
    files.
    - Consider adding dependency tasks to the `generateSources` task to do
      other source generation jobs.
  - You need to add `com.google.protobuf:protobuf-gradle-plugin` to
    `dependencies.toml` to get this to work.
  - Add `com.google.protobuf:protoc` to `dependencies.toml` to specify the
    compiler version.
  - Add `io.grpc:grpc-core` if you want to add gRPC plugin to the compiler.
  
    ```toml
    [libraries.protobuf-gradle-plugin]
    module = "com.google.protobuf:protobuf-gradle-plugin"
    version = "0.8.18"

    [libraries.protobuf-protoc]
    module = "com.google.protobuf:protoc"
    version = "3.19.2"
    ```

- The `.thrift` files under `src/*/thrift` will be compiled into Java code.

  - A new task called `generateSources` is added to all projects with `.thrift`
    files.
    - Consider adding dependency tasks to the `generateSources` task to do
      other source generation jobs.
  - Thrift compiler 0.16 will be used by default. Override `thriftVersion`
    property if you prefer 0.9:

    ```groovy
    ext {
        thriftVersion = '0.9'
        disableThriftJson() // Because Thrift 0.9 does not support JSON target
    }
    ```

  - You can also override the source and include directories:

    ```groovy
    ext {
        thriftSrcDirs = ["$projectDir/src/main/foo"]
        thriftIncludeDirs = ["$projectDir/src/main/foo-include"]
        testThriftSrcDirs = ["$projectDir/src/test/bar"]
        testThriftIncludeDirs = ["$projectDir/src/test/bar-include"]
    }
    ```

## Overriding JDK version

The scripts use [Toolchains](https://docs.gradle.org/current/userguide/toolchains.html) to build a Java
project. It uses [AdoptOpenJDK](https://adoptopenjdk.net/) 15 by default. If you want to use a
different JDK version, you can specify `buildJdkVersion` gradle property:

```
$ ./gradlew build -PbuildJdkVersion=11
```

You can also specify a different JRE version to run the tests via `testJavaVersion` gradle property:

```
$ ./gradlew test -PbuildJdkVersion=15 -PtestJavaVersion=8
```

## Publishing to Maven repository with `publish` flag

- Make sure `<project_root>/gradle.properties` and `~/.gradle/gradle.properties`
  are configured with correct publish settings.

  - For example, if `<project_root>/gradle.properties` has the following:

    ```
    publishUsernameProperty=ossrhUsername
    publishPasswordProperty=ossrhPassword
    ```

    `~/.gradle/gradle.properties` must have the following:

    ```
    ossrhUsername=<my_upload_username>
    ossrhPassword=<my_upload_password>
    ```

- PGP signing of artifacts is enabled if `publishSignatureRequired` property
  is `true` in `gradle.properties`.

  - You need to [configure signing plugin](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signatory_credentials)
    properly to use this feature.
  - Artifacts are signed only when `-Psign` option is specified or
    the artifact version does not end with `-SNAPSHOT`.

- For the projects with `java` flag:

  - Generates `META-INF/<groupId>.versions.properties` which contains some
    useful build information:

    ```
    myproject-foo.commitDate=2018-01-23 19\:14\:12 +0900
    myproject-foo.repoStatus=dirty
    myproject-foo.longCommitHash=2efe73d595a4687c9f8ad3d153ca8fe52604e20f
    myproject-foo.shortCommitHash=2efe73d5
    myproject-foo.version=0.0.1-SNAPSHOT
    ```

- If [Gradle Nexus Publish Plugin](https://github.com/gradle-nexus/publish-plugin) is enabled in 
 `<project_root>/build.gradle`, [staging function](https://help.sonatype.com/repomanager3/nexus-repository-administration/staging)
  is used to publish the artifacts. It is great for publishing your open source to Sonatype, and then to Maven 
  Central, in a fully automated fashion. 

  ```groovy
  // in build.gradle
  plugins {
    id 'io.github.gradle-nexus.publish-plugin' version '1.1.0'
  }
  ```

## Generating Maven BOM with `bom` flag

If you configure a project with `bom` flag, the project will be configured to
generate Maven BOM based on the dependencies specified in `dependencies.toml`.

`bom` flag implies `publish` flag, which means the BOM will be uploaded to a
Maven repository by `./gradlew publish`.

```groovy
// settings.gradle
includeWithFlags ':bom', 'bom'
```

If you want to publish multiple boms with different subprojects, you can use the `bomGroups` extension property.
Specify each bom's name with the subprojects:
```groovy
ext {
    bomGroups = [
            ':module1': [':module1:submodule1', ':module1:submodule2'],
            ':module2': [':module2:submodule1', ':module2:submodule2']
    ]
}
```

## Sharing [dependency versions](https://docs.gradle.org/current/userguide/platforms.html#sec:version-catalog-plugin) with `version-catalog` flag

If you configure a project with the `version-catalog` flag, the project will be configured to
publish version catalog based on the dependencies specified in `dependencies.toml`.

The `version-catalog` flag also implies `publish` flag, which means the `libs.versions.toml` will be uploaded to a
Maven repository by `./gradlew publish`.

```groovy
// settings.gradle
includeWithFlags ':version-catalog', 'version-catalog'
```

## Building shaded JARs with `shade` flag

Let's say you have a project that depends on a very old version of Guava and
you want to distribute the artifact that shades Guava to avoid the dependency
version conflict with other projects that uses the latest version of Guava.

You can generate a shaded JAR very easily by adding `shade` flag:

```groovy
// settings.gradle
rootProject.name = 'myproject'

apply from: "${rootDir}/gradle/scripts/settings-flags.gradle"

includeWithFlags ':foo', 'java', 'shade'
```

You need to add `relocations` property to `dependencies.toml` to tell which
dependency needs shading:

```yaml
[libraries.guava]
module = "com.google.guava:guava"
version = "17.0" # What an ancient dependency!
relocations [ { from: "com.google.common", to: "com.doe.john.myproject.shaded.guava" },
              { from: "com.google.thirdparty.publicsuffix", to: "com.doe.john.myproject.shaded.publicsuffix" } ]
```

### Trimming a shaded JAR with `trim` flag

If you shade many dependencies, your JAR will grow huge, even if you only use
a fraction of the classes in shaded dependencies. Use `trim` instead of `shade`,
then [ProGuard plugin](https://www.guardsquare.com/en/proguard/manual/gradle)
will strip the unused classes from the shaded JAR:

```groovy
// settings.gradle
// ...
includeWithFlags ':foo', 'java', 'trim' // 'trim' implies 'shade'.
```

You also need to configure the `trimShadedJar` task to tell ProGuard which
classes and members should not be stripped:

```groovy
// build.gradle
configure(projectsWithFlags('trim')) {
    tasks.trimShadedJar.configure {
        // Trim the classes under the shaded packages only.
        keep "class !com.doe.john.myproject.shaded.**,com.doe.john.myproject.** { *; }"
        // Whitelist the classes from Caffeine since it uses unsafe field access.
        keep "class com.doe.john.myproject.shaded.caffeine.** { *; }"
    }
}
```

See [ProGuard plugin manual](https://www.guardsquare.com/en/proguard/manual/gradle)
for more information.

### Shading a multi-module project with `relocate` flag

1. Choose the core or common project which will contain the shaded classes.
   Add `trim` or `shade` flag to it and `relocate` flag to the others:

   ```groovy
   // settings.gradle
   // ...
   includeWithFlags ':common', 'java', 'trim'
   includeWithFlags ':client', 'java', 'relocate'
   includeWithFlags ':server', 'java', 'relocate'
   ```

2. Add the shaded dependencies to *all* subprojects:

   ```groovy
   // <project_root>/build.gradle
   // ...
   configure(projectsWithFlags('java')) {
       dependencies {
           // Except ':common' itself.
           if (project != project(':common')) {
               compile project(':common')
           }
       }
   }
   ```

## Tagging conveniently with `release` task

The task called `release` is added at the top level project. It will update the
`version` property in `gradle.properties` to a release version, create a tag and
update the `version` property again to a next version.

```
$ ./gradlew release -PreleaseVersion=0.0.1 -PnextVersion=0.0.2
...
Tagged: myproject-0.0.1
...
```

By default, the version number must match `^[0-9]+\.[0-9]+\.[0-9]+$`. You can
override this by setting `versionPattern` property in `gradle.properties`:

```
# gradle.properties
# ...
# Regular expression. Note escaped backslashes.
versionPattern=^[0-9]+\\.[0-9]+\\.[0-9]+\\.(Beta[0-9]+|RC[0-9]+|Release)$
```

You can add `<project_root>/.post-release-msg` file to print some additional
instructions after tagging:

```
1. Upload the artifacts to the staging repository:

   git checkout ${tag}
   ./gradlew --no-daemon clean publish

2. Close and release the staging repository at:

   https://oss.sonatype.org/

3. Update the release note.
4. Deploy the web site.
```

Note the `${tag}`, which is replaced with the tag name.
See [Groovy `SimpleTemplateEngine`](http://docs.groovy-lang.org/docs/next/html/documentation/template-engines.html#_simpletemplateengine)
for the syntax.
