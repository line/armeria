/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.annotation.processor;

import static com.linecorp.armeria.internal.server.annotation.ProcessedDocumentationHelper.getFileName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.joor.CompileOptions;
import org.joor.Reflect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.common.io.CharStreams;

import com.linecorp.armeria.common.util.SystemInfo;

public class DocumentationProcessorTest {
    private final DocumentationProcessor target = new DocumentationProcessor();

    @BeforeAll
    public static void classSetup() {
        assumeThat(SystemInfo.javaVersion()).isGreaterThanOrEqualTo(13);
    }

    @AfterAll
    public static void classCleanUp() throws IOException {
        Files.deleteIfExists(Paths.get(
                "com.linecorp.armeria.docstrings.annotated.com.linecorp.armeria.WithJavaDoc.properties"));
        Files.deleteIfExists(Paths.get(
                "com.linecorp.armeria.docstrings.annotated.com.linecorp.armeria.NoJavaDoc.properties"));
    }

    @Test
    public void withJavaDoc() throws IOException {
        Reflect.compile(
                "com.linecorp.armeria.WithJavaDoc",
                loadFile("WithJavaDoc.java.txt"),
                new CompileOptions().processors(target)
        );
        testFile("com.linecorp.armeria.WithJavaDoc");
    }

    @Test
    public void noJavaDoc() throws IOException {
        Reflect.compile(
                "com.linecorp.armeria.NoJavaDoc",
                loadFile("NoJavaDoc.java.txt"),
                new CompileOptions().processors(target)
        );
        final String fileName = getFileName("com.linecorp.armeria.NoJavaDoc");
        assertThat(Files.notExists(Paths.get(fileName))).isTrue();
    }

    private static void testFile(String fileName) throws IOException {
        final Path path = Paths.get(getFileName(fileName));
        assertThat(Files.exists(path)).isTrue();
        final Properties properties = new Properties();
        properties.load(Files.newInputStream(path));
        assertThat(properties.getProperty("a.x")).isEqualTo("The x variable in a");
        assertThat(properties.getProperty("a.y")).isEqualTo("The y variable in a");
        assertThat(properties.getProperty("b.x")).isEqualTo("The x variable in b");
        assertThat(properties.getProperty("b.y")).isEqualTo("The y variable in b");
        assertThat(properties.getProperty("c.x")).isEqualTo("The x variable in c");
        assertThat(properties.getProperty("c.y")).isEqualTo("The y variable in c");
        assertThat(properties.getProperty("d.x")).isEqualTo("The x variable in d");
        assertThat(properties.getProperty("d.y")).isEqualTo("The y variable in d");
        assertThat(properties.getProperty("hasReturn.x")).isEqualTo("The x variable in hasReturn");
        assertThat(properties.getProperty("hasThrows.x")).isEqualTo("The x variable in hasThrows");
        assertThat(properties.getProperty("hasReturnAndThrows.x"))
                .isEqualTo("The x variable in hasReturnAndThrows");
        assertThat(properties.getProperty("hasMultilineComment.x"))
                .isEqualTo("The x variable in hasMultilineComment and this continues on the next line");
    }

    private String loadFile(String fileName) throws IOException {
        try (Reader reader = new InputStreamReader(
                this.getClass()
                    .getClassLoader()
                    .getResourceAsStream("testing/DocumentationProcessor/" + fileName))) {
            return CharStreams.toString(reader);
        }
    }
}
