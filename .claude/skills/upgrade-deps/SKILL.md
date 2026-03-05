---
name: upgrade-deps
description: Upgrade Gradle dependencies and Gradle wrapper version
disable-model-invocation: true
---

# Dependency Upgrade Workflow

This skill performs a full Gradle dependency upgrade for the Armeria project step by step.

## Background: Minimum Java Version Per Module

Armeria is a multi-module project with different minimum Java version requirements.
Before upgrading a dependency, always verify that the new version supports the minimum
Java version required by the modules that use it.

| Java Version | Modules |
|-------------|---------|
| **8**  | `core`, `brave5`, `brave6`, `eureka`, `grpc`, `grpc-kotlin`, `graphql-protocol`, `jetty9`, `junit4`, `junit5`, `kafka`, `kotlin`, `logback`, `logback12`, `logback13`, `micrometer-context`, `oauth2`, `prometheus1`, `protobuf`, `reactor3`, `resteasy`, `retrofit2`, `rxjava2`, `rxjava3`, `sangria`, `scala*`, `spring:boot2-*`, `dropwizard2`, `thrift0.9`–`thrift0.17`, `tomcat8`, `tomcat9`, `xds`, `zookeeper3`, `saml`, `bucket4j`, `consul`, `nacos` |
| **11** | `athenz`, `graphql`, `jetty10`, `jetty11`, `kubernetes`, `logback14`, `thrift0.18`–`thrift0.22`, `tomcat10` |
| **17** | `ai:mcp`, `jetty12`, `resilience4j2`, `spring:boot3-*`, `spring:spring6`, `spring:boot4-*`, `spring:spring7` |

When in doubt, check `settings.gradle` for the `java`, `java11`, or `java17` flag on each subproject.

## Step 1: Run dependencyUpdates

```
./gradlew dependencyUpdates
```

The report is saved to `build/dependencyUpdates/report.txt`.

## Step 2: Parse the Report

Read `build/dependencyUpdates/report.txt` to identify all outdated dependencies.
For each dependency, note the current version and the available latest version.

If a version looks ambiguous (unexpected format, major version bump, unusual naming),
verify on Maven Central before proceeding:
  https://central.sonatype.com/artifact/{groupId}/{artifactId}

## Step 3: Check Java Version Compatibility

For each candidate upgrade:
1. Find which modules use this dependency in `dependencies.toml`
2. Determine the minimum Java version required by those modules (see table above)
3. Check the new library version's minimum Java requirement (Maven Central / release notes)
4. If the library's required Java > module's minimum Java → **skip the upgrade**
5. If compatible → proceed with the upgrade

## Step 4: Update dependencies.toml

Edit the `[versions]` section in `dependencies.toml` to update the version strings.
The file is located at the root of the repository: `dependencies.toml`.

## Step 5: Upgrade Gradle Wrapper

Check the latest stable Gradle release at https://gradle.org/releases/ and update
`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-X.Y.Z-all.zip
```

## Step 6: Verify the Build

Run the full build including tests to catch both compilation errors and runtime regressions:

```
./gradlew build --no-daemon
```

If there are compilation errors, API breaking changes, or test failures caused by the upgrade,
fix them before proceeding.

## Step 7: Commit

Create a commit with the **exact** message format below. Follow it strictly — do not add extra
sections, reorder bullets, or change the structure:

```
Update dependencies

- {library-name} {old-version} -> {new-version}
- {library-name} {old-version} -> {new-version}
- Build
   - {library-name} {old-version} -> {new-version}
   - {library-name} {old-version} -> {new-version}
```

Rules:
- Each production dependency (api/implementation scope) gets one bullet: `- {name} {old} -> {new}`
- The `- Build` bullet groups build-only dependencies (testImplementation, annotationProcessor,
  relocated libs, non-transitive deps); nest them as sub-bullets with 3-space indent
- Use the exact library name as it appears in `dependencies.toml` (the version key name)
- Sort entries **alphabetically (A → Z)** within each section
- Omit skipped dependencies from the commit message entirely
- If no build-only deps were upgraded, omit the `- Build` section entirely

Example:
```
Update dependencies

- grpc-java 1.63.0 -> 1.64.0
- jackson 2.17.0 -> 2.18.0
- Build
   - checkstyle 10.14.0 -> 10.17.0
   - errorprone 2.27.0 -> 2.28.0
```
