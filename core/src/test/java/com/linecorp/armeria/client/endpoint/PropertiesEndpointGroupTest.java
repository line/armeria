/*
 * Copyright 2018 LINE Corporation
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

import java.util.Properties;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class PropertiesEndpointGroupTest {

    private static final Properties PROPS = new Properties();

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
    public void testWithPrefixThatEndsWithDot() {
        final PropertiesEndpointGroup endpointGroup = PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts.");

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(Endpoint.parse("127.0.0.1:8080"),
                                                                        Endpoint.parse("127.0.0.1:8081"),
                                                                        Endpoint.parse("127.0.0.1"));
    }

    @Test
    public void containsNoHosts() {
        assertThatThrownBy(() -> PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverC.hosts", 8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains no hosts");
    }

    @Test
    public void illegalDefaultPort() {
        assertThatThrownBy(() -> PropertiesEndpointGroup.of(
                getClass().getClassLoader(), "server-list.properties", "serverA.hosts", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultPort");
    }
}
