/*
 * Copyright 2026 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableMap;

class DocStringExtractorTest {

    private static final String DOCSTRING_DIR = "META-INF/armeria/docstringextractortest";

    @TempDir
    Path tempDir;

    @Test
    void shouldNotCloseSharedJarFile() throws Exception {
        final Path jar = tempDir.resolve("docstrings.jar");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(os)) {
            out.putNextEntry(new JarEntry(DOCSTRING_DIR + '/'));
            out.closeEntry();
            out.putNextEntry(new JarEntry(DOCSTRING_DIR + "/foo.txt"));
            out.write("bar".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(DOCSTRING_DIR + "/ignored.txt"));
            out.write("ignored".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        final URL jarUrl = new URL("jar:" + jar.toUri() + "!/");

        try (JarFile sharedJarFile = ((JarURLConnection) jarUrl.openConnection()).getJarFile();
             URLClassLoader classLoader = new URLClassLoader(new URL[] { jarUrl }, null)) {
            assertThat(new TestDocStringExtractor().getAllDocStrings(classLoader))
                    .containsExactlyEntriesOf(ImmutableMap.of(DOCSTRING_DIR + "/foo.txt", "bar"));
            assertThat(sharedJarFile.getEntry(DOCSTRING_DIR + "/foo.txt")).isNotNull();
        }
    }

    private static final class TestDocStringExtractor extends DocStringExtractor {

        TestDocStringExtractor() {
            super(DOCSTRING_DIR, "com.linecorp.armeria.docstringextractortest.dir");
        }

        @Override
        protected boolean acceptFile(String filename) {
            return "foo.txt".equals(filename);
        }

        @Override
        protected Map<String, String> getDocStringsFromFiles(Map<String, byte[]> files) {
            return files.entrySet().stream().collect(toImmutableMap(
                    Entry::getKey, e -> new String(e.getValue(), StandardCharsets.UTF_8)));
        }
    }
}
