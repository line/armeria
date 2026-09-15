/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.it.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Makes sure a user does not have to upgrade Kotlin in order to upgrade Armeria.
 *
 * <p>A Kotlin binary is readable only by the compiler of the same or the next minor version, and the
 * compiler reads every Kotlin binary on the compile classpath whether or not the user's code touches it.
 * So the oldest Kotlin a user may stay on is decided by two things Armeria publishes: the metadata
 * version of its own classes, and the version of the kotlin-stdlib it declares at compile scope. Both
 * are pinned to {@code kotlin-baseline} in {@code dependencies.toml}, which lets users one minor older
 * than the baseline keep compiling.
 *
 * <p>This test reads the same classpath a user's Kotlin compiler would have to read. It fails when
 * something raises that floor again - the {@code kotlin2.x} flag disappearing from
 * {@code settings.gradle}, the Kotlin Gradle plugin going back to injecting its own kotlin-stdlib, or a
 * newly added compile scope dependency that was built with a newer Kotlin.
 */
class KotlinBaselineCompatibilityTest {

    private static final Pattern JAR_VERSION = Pattern.compile("^(.*)-(\\d+\\.\\d+\\.\\d+.*)\\.jar$");

    private static int[] baseline;
    private static Map<File, int[]> metadataVersions;

    @BeforeAll
    static void scanCompileClasspath() {
        baseline = parseVersion(systemProperty("armeria.kotlin.baseline"));

        metadataVersions = new LinkedHashMap<>();
        for (String path : systemProperty("armeria.kotlin.compileClasspath").split(File.pathSeparator)) {
            final File jar = new File(path);
            final int[] version = metadataVersion(jar);
            if (version != null) {
                metadataVersions.put(jar, version);
            }
        }

        // An empty or half-resolved classpath would make every assertion below pass without testing
        // anything, so make sure the modules under test are actually there.
        assertThat(metadataVersions.keySet().stream().map(File::getName))
                .as("Kotlin binaries on the compile classpath")
                .anyMatch(name -> name.startsWith("armeria-kotlin-"))
                .anyMatch(name -> name.startsWith("armeria-grpc-kotlin-"))
                .anyMatch(name -> name.startsWith("kotlin-stdlib-"));
    }

    @Test
    void everyKotlinBinaryIsReadableByTheOldestSupportedCompiler() {
        final List<String> tooNew = new ArrayList<>();
        metadataVersions.forEach((jar, version) -> {
            if (compareMinor(version, baseline) > 0) {
                tooNew.add(jar.getName() + " has metadata version " + format(version));
            }
        });

        assertThat(tooNew)
                .as("Kotlin binaries a user's compiler would fail to read. Every entry must be at or " +
                    "below the %s baseline; raising one of them forces every user onto a newer Kotlin.",
                    format(baseline))
                .isEmpty();
    }

    @Test
    void armeriaKotlinModulesAreCompiledAtTheBaseline() {
        final Map<String, String> compiledAt = new LinkedHashMap<>();
        metadataVersions.forEach((jar, version) -> {
            if (jar.getName().startsWith("armeria-")) {
                compiledAt.put(jar.getName(), version[0] + "." + version[1]);
            }
        });
        final String expected = baseline[0] + "." + baseline[1];

        // Catches a `kotlin-baseline` bump that forgets the flag, which would otherwise leave the
        // modules compiled at the old minor while shipping a newer kotlin-stdlib.
        assertThat(compiledAt.values())
                .as("Kotlin minor the Armeria modules were compiled at, per %s. Keep the 'kotlin<x.y>' " +
                    "flag in settings.gradle in sync with the 'kotlin-baseline' version in " +
                    "dependencies.toml.", compiledAt)
                .isNotEmpty()
                .containsOnly(expected);
    }

    @Test
    void kotlinStdlibIsPinnedToTheBaseline() {
        final String expected = systemProperty("armeria.kotlin.baseline");
        final List<String> stdlibs = new ArrayList<>();
        for (File jar : metadataVersions.keySet()) {
            final Matcher matcher = JAR_VERSION.matcher(jar.getName());
            if (matcher.matches() && "kotlin-stdlib".equals(matcher.group(1))) {
                stdlibs.add(matcher.group(2));
            }
        }

        assertThat(stdlibs)
                .as("kotlin-stdlib published at compile scope. It must stay at the %s baseline; the " +
                    "Kotlin Gradle plugin otherwise injects its own version and upgrades every user.",
                    expected)
                .containsExactly(expected);
    }

    @Test
    void retainsHighestKotlinMetadataVersionInAnyPosition(@TempDir Path tempDir) throws IOException {
        assertHighestMetadataVersion(tempDir.resolve("first.jar"),
                                     new int[] { 2, 3, 0 }, new int[] { 2, 2, 0 }, new int[] { 2, 1, 0 });
        assertHighestMetadataVersion(tempDir.resolve("middle.jar"),
                                     new int[] { 2, 2, 0 }, new int[] { 2, 3, 0 }, new int[] { 2, 1, 0 });
        assertHighestMetadataVersion(tempDir.resolve("last.jar"),
                                     new int[] { 2, 2, 0 }, new int[] { 2, 1, 0 }, new int[] { 2, 3, 0 });
    }

    @Test
    void scansDuplicateKotlinModuleNames(@TempDir Path tempDir) throws IOException {
        assertHighestDuplicateMetadataVersion(tempDir.resolve("first.jar"),
                                              new int[] { 2, 3, 0 }, new int[] { 2, 2, 0 },
                                              new int[] { 2, 1, 0 });
        assertHighestDuplicateMetadataVersion(tempDir.resolve("middle.jar"),
                                              new int[] { 2, 2, 0 }, new int[] { 2, 3, 0 },
                                              new int[] { 2, 1, 0 });
        assertHighestDuplicateMetadataVersion(tempDir.resolve("last.jar"),
                                              new int[] { 2, 2, 0 }, new int[] { 2, 1, 0 },
                                              new int[] { 2, 3, 0 });
    }

    private static int[] metadataVersion(File jar) {
        if (!jar.isFile()) {
            return null;
        }
        try (InputStream in = Files.newInputStream(jar.toPath());
             JarInputStream jarInput = new JarInputStream(in);
             DataInputStream data = new DataInputStream(jarInput)) {
            int[] highest = null;
            JarEntry entry;
            while ((entry = jarInput.getNextJarEntry()) != null) {
                if (entry.getName().startsWith("META-INF/") &&
                    entry.getName().endsWith(".kotlin_module")) {
                    final int[] version = readMetadataVersion(data, entry, jar);
                    if (highest == null || compareVersion(version, highest) > 0) {
                        highest = version;
                    }
                }
                jarInput.closeEntry();
            }
            return highest;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + jar, e);
        }
    }

    private static void assertHighestMetadataVersion(Path jar, int[]... versions) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < versions.length; i++) {
                writeMetadataVersion(out, "module" + i, versions[i]);
            }
        }
        assertThat(metadataVersion(jar.toFile())).containsExactly(2, 3, 0);
    }

    private static void assertHighestDuplicateMetadataVersion(Path jar, int[]... versions)
            throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < versions.length; i++) {
                writeMetadataVersion(out, "module" + i, versions[i]);
            }
        }

        // JarOutputStream rejects duplicate names, so patch equal-length names in both ZIP records.
        final byte[] content = Files.readAllBytes(jar);
        String zip = new String(content, StandardCharsets.ISO_8859_1);
        for (int i = 1; i < versions.length; i++) {
            zip = zip.replace("module" + i, "module0");
        }
        Files.write(jar, zip.getBytes(StandardCharsets.ISO_8859_1));

        final String[] expectedNames = new String[versions.length];
        Arrays.fill(expectedNames, "META-INF/module0.kotlin_module");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            assertThat(jarFile.stream()
                              .map(JarEntry::getName)
                              .filter(name -> name.endsWith(".kotlin_module")))
                    .containsExactly(expectedNames);
        }
        assertThat(metadataVersion(jar.toFile())).containsExactly(2, 3, 0);
    }

    private static void writeMetadataVersion(JarOutputStream out, String name, int... version)
            throws IOException {
        out.putNextEntry(new JarEntry("META-INF/" + name + ".kotlin_module"));
        final DataOutputStream data = new DataOutputStream(out);
        data.writeInt(version.length);
        for (int component : version) {
            data.writeInt(component);
        }
        out.closeEntry();
    }

    private static int[] readMetadataVersion(DataInputStream data, JarEntry entry, File jar)
            throws IOException {
        // A .kotlin_module starts with the metadata version: the number of components followed by
        // the components themselves, all as big-endian ints.
        final int length = data.readInt();
        assertThat(length).as("metadata version length of %s in %s", entry.getName(), jar.getName())
                          .isBetween(2, 8);
        final int[] version = new int[length];
        for (int i = 0; i < length; i++) {
            version[i] = data.readInt();
        }
        return version;
    }

    private static int compareVersion(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static int compareMinor(int[] a, int[] b) {
        if (a[0] != b[0]) {
            return Integer.compare(a[0], b[0]);
        }
        return Integer.compare(a[1], b[1]);
    }

    private static int[] parseVersion(String version) {
        final String[] components = version.split("\\.");
        final int[] parsed = new int[components.length];
        for (int i = 0; i < components.length; i++) {
            parsed[i] = Integer.parseInt(components[i]);
        }
        return parsed;
    }

    private static String format(int[] version) {
        return Arrays.stream(version)
                     .mapToObj(Integer::toString)
                     .reduce((a, b) -> a + '.' + b)
                     .orElseThrow(IllegalStateException::new);
    }

    private static String systemProperty(String name) {
        final String value = System.getProperty(name);
        assertThat(value).as("system property '%s', set by build.gradle.kts", name).isNotBlank();
        return value;
    }
}
