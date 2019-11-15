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
import java.net.URL;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.armeria.client.Endpoint;

public class PropertiesEndpointGroupTest {

    private static final Properties PROPS = new Properties();

    @BeforeClass
    public static void before() {
        Awaitility.setDefaultTimeout(1, TimeUnit.MINUTES);
    }

    @AfterClass
    public static void after() {
        Awaitility.setDefaultTimeout(10, TimeUnit.SECONDS);
    }

    @After
    public void tearDown() throws Exception {
        PropertiesEndpointGroup.resetRegistry();
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    static {
        PROPS.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        PROPS.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        PROPS.setProperty("serverA.hosts.2", "127.0.0.1");
        PROPS.setProperty("serverB.hosts.0", "127.0.0.1:8082");
        PROPS.setProperty("serverB.hosts.1", "127.0.0.1:8083");
    }

    @Test
    public void propertiesWithoutDefaultPort() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(PROPS, "serverA.hosts");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    public void propertiesWithDefaultPort() {
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(PROPS, "serverA.hosts", 80);
        final PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.of(PROPS, "serverB.hosts", 8080);

        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        assertThat(endpointGroupB.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8082"),
                                                                         Endpoint.parse("127.0.0.1:8083"));
    }

    @Test
    public void resourceWithoutDefaultPort() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    public void resourceWithDefaultPort() {
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts", 80);
        final PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverB.hosts", 8080);
        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        assertThat(endpointGroupB.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8082"),
                                                                         Endpoint.parse("127.0.0.1:8083"));
    }

    @Test
    public void pathWithDefaultPort() throws Exception {
        final URL resourceUrl = getClass().getClassLoader().getResource("server-list.properties");
        assert resourceUrl != null;
        final Path resourcePath = new File(resourceUrl.getFile()).toPath();
        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                resourcePath, "serverA.hosts", 80);
        assertThat(endpointGroupA.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                         Endpoint.parse("127.0.0.1:8081"),
                                                                         Endpoint.parse("127.0.0.1:80"));
        endpointGroupA.close();
    }

    @Test
    public void pathWithoutDefaultPort() {
        final URL resourceUrl = getClass().getClassLoader().getResource("server-list.properties");
        assert resourceUrl != null;
        final Path resourcePath = new File(resourceUrl.getFile()).toPath();
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                resourcePath, "serverA.hosts");
        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
        endpointGroup.close();
    }

    @Test
    public void testWithPrefixThatEndsWithDot() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts.");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    public void containsNoHosts() {
        assertThat(PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverC.hosts",
                8080).endpoints()
        ).isEmpty();
    }

    @Test
    public void illegalDefaultPort() {
        assertThatThrownBy(() -> PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPort");
    }

    @Test
    public void propertiesFileUpdatesCorrectly() throws Exception {
        final File file = folder.newFile("temp-file.properties");

        PrintWriter printWriter = new PrintWriter(file);
        Properties props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.store(printWriter, "");
        printWriter.close();

        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                file.toPath(), "serverA.hosts");

        await().untilAsserted(() -> assertThat(endpointGroupA.endpoints()).hasSize(1));

        // Update resource
        printWriter = new PrintWriter(file);
        props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        props.store(printWriter, "");
        printWriter.close();

        await().untilAsserted(() -> assertThat(endpointGroupA.endpoints()).hasSize(2));

        endpointGroupA.close();
    }

    @Test
    public void duplicateResourceUrl() throws IOException {
        final File file = folder.newFile("temp-file.properties");
        final PropertiesEndpointGroup propertiesEndpointGroupA =
                PropertiesEndpointGroup.of(file.toPath(), "serverA.hosts");
        final PropertiesEndpointGroup propertiesEndpointGroupB =
                PropertiesEndpointGroup.of(file.toPath(), "serverA.hosts");
        propertiesEndpointGroupA.close();
        propertiesEndpointGroupB.close();
    }

    @Test
    public void propertiesFileRestart() throws Exception {
        final File file = folder.newFile("temp-file.properties");

        PrintWriter printWriter = new PrintWriter(file);
        Properties props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.store(printWriter, "");
        printWriter.close();

        final PropertiesEndpointGroup endpointGroupA = PropertiesEndpointGroup.of(
                file.toPath(), "serverA.hosts");
        await().untilAsserted(() -> assertThat(endpointGroupA.endpoints()).hasSize(1));
        endpointGroupA.close();

        final PropertiesEndpointGroup endpointGroupB = PropertiesEndpointGroup.of(
                file.toPath(), "serverB.hosts");
        await().untilAsserted(() -> assertThat(endpointGroupB.endpoints()).isEmpty());

        printWriter = new PrintWriter(file);
        props = new Properties();
        props.setProperty("serverB.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverB.hosts.1", "127.0.0.1:8081");
        props.store(printWriter, "");
        printWriter.close();

        await().untilAsserted(() -> assertThat(endpointGroupB.endpoints()).hasSize(2));
        endpointGroupB.close();
    }

    @Test
    public void endpointChangePropagatesToListeners() throws Exception {
        final File file = folder.newFile("temp-file.properties");

        PrintWriter printWriter = new PrintWriter(file);
        Properties props = new Properties();
        props.setProperty("serverA.hosts.0", "127.0.0.1:8080");
        props.setProperty("serverA.hosts.1", "127.0.0.1:8081");
        props.store(printWriter, "");
        printWriter.close();

        final PropertiesEndpointGroup propertiesEndpointGroup = PropertiesEndpointGroup.of(
                file.toPath(), "serverA.hosts");
        final EndpointGroup fallbackEndpointGroup = Endpoint.of("127.0.0.1", 8081);
        final EndpointGroup endpointGroup = propertiesEndpointGroup.orElse(fallbackEndpointGroup);

        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSize(2));

        printWriter = new PrintWriter(file);
        props = new Properties();
        props.store(printWriter, "");
        printWriter.close();

        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSize(1));

        printWriter = new PrintWriter(file);
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
