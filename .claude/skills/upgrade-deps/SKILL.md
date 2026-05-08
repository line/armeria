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
./gradlew dependencyUpdates --no-parallel
```

The report is saved to `build/dependencyUpdates/report.txt`.

## Step 2: Parse the Report

Read `build/dependencyUpdates/report.txt` to identify all outdated dependencies.
For each dependency, note the current version and the available latest version.

If a version looks ambiguous (unexpected format, major version bump, unusual naming),
verify on Maven Central before proceeding:
  https://central.sonatype.com/artifact/{groupId}/{artifactId}

## Step 3: Check Skip Hints and Java Version Compatibility

Before upgrading any dependency, check `dependencies.toml` for a comment directly above
its version entry. Many pinned versions have an explicit reason, for example:

```toml
# Don't upgrade Caffeine to 3.x that requires Java 11.
caffeine = "2.9.3"

# Upgrade once https://github.com/ronmamo/reflections/issues/279 is fixed.
reflections = "0.9.11"

# Ensure that we use the same ZooKeeper version as what Curator depends on.
zookeeper = "3.9.3"
```

If such a comment exists and the reason still applies, **skip the upgrade** and do not
remove the comment.

If no comment exists, proceed with the Java version compatibility check:
1. Find which modules use this dependency in `dependencies.toml`
2. Determine the minimum Java version required by those modules (see table above)
3. Check the new library version's minimum Java requirement (Maven Central / release notes)
4. If the library's required Java > module's minimum Java → **skip the upgrade** and add a
   comment above the version entry explaining why (e.g. `# X.Y requires Java 11`)
5. If compatible → proceed with the upgrade

## Step 4: Update dependencies.toml

Edit the `[versions]` section in `dependencies.toml` to update the version strings.
The file is located at the root of the repository: `dependencies.toml`.

## Step 5: Cross-check Updated Versions

After editing `dependencies.toml`, re-read the full diff (`git diff dependencies.toml`) and
cross-check every changed entry against the `dependencyUpdates` report:

1. **No entry was accidentally skipped** — compare the report's "The following dependencies
   have later milestone versions" list against your edits. If a dependency appears in the
   report but not in the diff, either upgrade it or document why it was skipped.
2. **Versions match the report** — confirm each new version in the diff matches the latest
   version from the report, not a typo or intermediate version.
3. **Pinned-version comments were respected** — verify you did not upgrade a dependency whose
   comment says to skip it, and that you did not remove or contradict an existing comment.
4. **Linked-version constraints are satisfied** — some dependencies must stay in sync with
   others (e.g. ZooKeeper with Curator, Protobuf with gRPC). Read the comments above those
   entries and verify the constraint still holds after the upgrade.

## Step 6: Upgrade Gradle Wrapper

Check the latest stable Gradle release at https://gradle.org/releases/ and update
`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-X.Y.Z-all.zip
```

## Step 7: Verify the Build

Run the full build including tests to catch both compilation errors and runtime regressions:

```
./gradlew build --no-daemon
```

If there are compilation errors, API breaking changes, or test failures caused by the upgrade,
fix them before proceeding.

## Step 8: Commit

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
- Use the library's official name if one exists (e.g. `gRPC-Java`, `Jackson`, `Netty`, `Kotlin`,
  `Reactor`, `Logback`, `Micrometer`); otherwise use the key name as-is from `dependencies.toml`
- Sort entries **alphabetically (A → Z)** within each section
- If no build-only deps were upgraded, omit the `- Build` section entirely
- If any dependencies were **not upgraded** (due to Java version constraints, pinned-version
  comments, or linked-version constraints), list them under a `- Unupdated` section with the
  reason. This helps reviewers know which upgrades were intentionally skipped.

Example:
```
Update dependencies

- gRPC-Java 1.63.0 -> 1.64.0
- Jackson 2.17.0 -> 2.18.0
- Build
   - checkstyle 10.14.0 -> 10.17.0
   - ErrorProne 2.27.0 -> 2.28.0
- Unupdated
   - Caffeine 2.9.3 -> 3.2.0 (requires Java 11)
   - ZooKeeper 3.9.3 (pinned to Curator's version)
```
