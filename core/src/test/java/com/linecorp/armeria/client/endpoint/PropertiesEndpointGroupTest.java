/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.client.Endpoint;

class PropertiesEndpointGroupTest {

    private static final String RESOURCE_PATH =
            "testing/core/" + PropertiesEndpointGroupTest.class.getSimpleName() + "/server-list.properties";

    private static final Properties PROPS = new Properties();

    @BeforeAll
    static void before() {
        Awaitility.setDefaultTimeout(1, TimeUnit.MINUTES);
    }

    @AfterAll
    static void after() {
        Awaitility.setDefaultTimeout(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        PropertiesEndpointGroup.resetRegistry();
    }

    @TempDir
    Path folder;

    static {
        PROPS.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        PROPS.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        PROPS.setProperty("serverA.hosts.2", "127.0.0.1");
        PROPS.setProperty("serverB.hosts.0", "127.0.0.1:8082");
        PROPS.setProperty("serverB.hosts.1", "127.0.0.1:8083");
    }

    @Test
    void propertiesWithoutDefaultPort() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(PROPS, "serverA.hosts");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    void propertiesWithDefaultPort() {
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.builder(PROPS, "serverA.hosts")
                                                                              .defaultPort(80)
                                                                              .build();
        final PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.builder(PROPS, "serverB.hosts")
                                                                              .defaultPort(8080)
                                                                              .build();

        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        assertThat(endpointGroupB.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8082"),
                                                                         Endpoint.parse("127.0.0.1:8083"));
    }

    @Test
    void resourceWithoutDefaultPort() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), RESOURCE_PATH, "serverA.hosts");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    void resourceWithDefaultPort() {
        final PropertiesEndpointGroup endpointGroupA =
                PropertiesEndpointGroup.builder(getClass().getClassLoader(),
                                                RESOURCE_PATH,
                                                "serverA.hosts")
                                       .defaultPort(80)
                                       .build();

        final PropertiesEndpointGroup endpointGroupB =
                PropertiesEndpointGroup.builder(getClass().getClassLoader(),
                                                RESOURCE_PATH,
                                                "serverB.hosts")
                                       .defaultPort(8080)
                                       .build();

        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        assertThat(endpointGroupB.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8082"),
                                                                         Endpoint.parse("127.0.0.1:8083"));
    }

    @Test
    void pathWithDefaultPort() throws Exception {
        final URL resourceUrl = getClass().getClassLoader().getResource(RESOURCE_PATH);
        assertThat(resourceUrl).isNotNull();
        final Path resourcePath = new File(resourceUrl.toURI().getPath()).toPath();
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.builder(
                resourcePath, "serverA.hosts").defaultPort(80).build();
        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        endpointGroupA.close();
    }

    @Test
    void pathWithoutDefaultPort() throws URISyntaxException {
        final URL resourceUrl = getClass().getClassLoader().getResource(RESOURCE_PATH);
        assertThat(resourceUrl).isNotNull();
        final Path resourcePath = new File(resourceUrl.toURI().getPath()).toPath();
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                resourcePath, "serverA.hosts");
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
        endpointGroup.close();
    }

    @Test
    void testWithPrefixThatEndsWithDot() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), RESOURCE_PATH, "serverA.hosts.");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    void containsNoHosts() {
        assertThat(PropertiesEndpointGroup.builder(getClass().getClassLoader(),
                                                   RESOURCE_PATH, "serverC.hosts")
                                          .defaultPort(8080)
                                          .build()
                                          .endpoints()).isEmpty();
    }

    @Test
    void illegalDefaultPort() {
        assertThatThrownBy(() -> PropertiesEndpointGroup.builder(getClass().getClassLoader(),
                                                                 RESOURCE_PATH, "serverA.hosts")
                                                        .defaultPort(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPort");
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_17)
    // NIO.2 WatchService doesn't work reliably on older Java.
    void propertiesFileUpdatesCorrectly() throws Exception {
        final Path file = folder.resolve("temp-file.properties");

        PrintWriter printWriter = new PrintWriter(file.toFile());
        Properties props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.store(printWriter, "");
        printWriter.close();

        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                file, "serverA.hosts");

        await().untilAsserted(() -> assertThat(endpointGroupA.endpoints()).hasSize(1));

        // Update resource
        printWriter = new PrintWriter(file.toFile());
        props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        props.store(printWriter, "");
        printWriter.close();

        await().untilAsserted(() -> assertThat(endpointGroupA.endpoints()).hasSize(2));

        endpointGroupA.close();
    }

    @Test
    void duplicateResourceUrl() throws IOException {
        final Path file = Files.createFile(folder.resolve("temp-file.properties"));
        final PropertiesEndpointGroup propertiesEndpointGroupA =
                PropertiesEndpointGroup.of(file, "serverA.hosts");
        final PropertiesEndpointGroup propertiesEndpointGroupB =
                PropertiesEndpointGroup.of(file, "serverA.hosts");
        propertiesEndpointGroupA.close();
        propertiesEndpointGroupB.close();
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_17)
    // NIO.2 WatchService doesn't work reliably on older Java.
    void propertiesFileRestart() throws Exception {
        final Path file = folder.resolve("temp-file.properties");

        PrintWriter printWriter = new PrintWriter(file.toFile());
        Properties props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.store(printWriter, "");
        printWriter.close();

        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                file, "serverA.hosts");
        await().untilAsserted(() -> assertThat(endpointGroupA.endpoints()).hasSize(1));
        endpointGroupA.close();

        final PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.of(
                file, "serverB.hosts");
        await().untilAsserted(() -> assertThat(endpointGroupB.endpoints()).isEmpty());

        printWriter = new PrintWriter(file.toFile());
        props = new Properties();
        props.setProperty("serverB.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverB.hosts.1", "127.0.0.1:8081");
        props.store(printWriter, "");
        printWriter.close();

        await().untilAsserted(() -> assertThat(endpointGroupB.endpoints()).hasSize(2));
        endpointGroupB.close();
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_17)
    // NIO.2 WatchService doesn't work reliably on older Java.
    void endpointChangePropagatesToListeners() throws Exception {
        final Path file = folder.resolve("temp-file.properties");

        PrintWriter printWriter = new PrintWriter(file.toFile());
        Properties props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        props.store(printWriter, "");
        printWriter.close();

        final PropertiesEndpointGroup propertiesEndpointGroup = PropertiesEndpointGroup.of(
                file, "serverA.hosts");
        final EndpointGroup fallbackEndpointGroup = Endpoint.of("127.0.0.1", 8081);
        final EndpointGroup endpointGroup = propertiesEndpointGroup.orElse(fallbackEndpointGroup);

        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSize(2));

        printWriter = new PrintWriter(file.toFile());
        props = new Properties();
        props.store(printWriter, "");
        printWriter.close();

        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSize(1));

        printWriter = new PrintWriter(file.toFile());
        props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        props.setProperty("serverA.hosts.2", "127.0.0.1:8082");
        props.store(printWriter, "");
        printWriter.close();

        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSize(3));
        propertiesEndpointGroup.close();
    }
}
