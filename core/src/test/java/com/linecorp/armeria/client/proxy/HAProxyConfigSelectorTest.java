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

package com.linecorp.armeria.client.proxy;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServiceRequestContext;

public class HAProxyConfigSelectorTest {
    final ProxyConfigSelector selector = ProxyConfigSelector.haproxy();

    @Test
    void testEndpointUsedWhenNoContext() {
        final Endpoint endpoint = Endpoint.of("some.host", 80).withIpAddr("127.0.0.1");
        final HAProxyConfig proxyConfig = (HAProxyConfig) selector.select(HTTP, endpoint);
        assertThat(proxyConfig.proxyAddress()).isNotNull();
        assertThat(proxyConfig.proxyAddress().getHostString()).isEqualTo(endpoint.ipAddr());
        assertThat(proxyConfig.proxyAddress().getPort()).isEqualTo(endpoint.port());
        assertThat(proxyConfig.sourceAddress()).isNull();
    }

    @Test
    void testEndpointUsedWhenOnlyClientContext() {
        final ClientRequestContext clientCtx =
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
        try (SafeCloseable ignored = clientCtx.push()) {
            final Endpoint endpoint = Endpoint.of("some.host", 80).withIpAddr("127.0.0.1");
            final HAProxyConfig proxyConfig = (HAProxyConfig) selector.select(HTTP, endpoint);
            assertThat(proxyConfig.proxyAddress()).isNotNull();
            assertThat(proxyConfig.proxyAddress().getHostString()).isEqualTo(endpoint.ipAddr());
            assertThat(proxyConfig.proxyAddress().getPort()).isEqualTo(endpoint.port());
            assertThat(proxyConfig.sourceAddress()).isNull();
        }
    }

    @Test
    void testProxiedAddressesDestEmpty() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ProxiedAddresses proxiedAddresses =
                ProxiedAddresses.of(new InetSocketAddress("127.0.0.1", 81));
        final ServiceRequestContext serviceCtx =
                ServiceRequestContext.builder(req).proxiedAddresses(proxiedAddresses).build();

        try (SafeCloseable ignored = serviceCtx.push()) {
            final ClientRequestContext clientCtx = ClientRequestContext.builder(req).build();
            try (SafeCloseable ignored2 = clientCtx.push()) {
                final Endpoint endpoint = Endpoint.of("some.host", 83).withIpAddr("127.0.0.3");
                final HAProxyConfig proxyConfig = (HAProxyConfig) selector.select(HTTP, endpoint);
                assertThat(proxyConfig.sourceAddress()).isNull();
                assertThat(proxyConfig.proxyAddress()).isNotNull();
                assertThat(proxyConfig.proxyAddress().getHostString()).isEqualTo(endpoint.ipAddr());
                assertThat(proxyConfig.proxyAddress().getPort()).isEqualTo(endpoint.port());
            }
        }
    }

    @Test
    void testServiceRequestContextUsed() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ProxiedAddresses proxiedAddresses =
                ProxiedAddresses.of(new InetSocketAddress("127.0.0.1", 81),
                                    new InetSocketAddress("127.0.0.2", 82));
        final ServiceRequestContext serviceCtx =
                ServiceRequestContext.builder(req).proxiedAddresses(proxiedAddresses).build();

        try (SafeCloseable ignored = serviceCtx.push()) {
            final ClientRequestContext clientCtx = ClientRequestContext.builder(req).build();
            try (SafeCloseable ignored2 = clientCtx.push()) {
                final Endpoint endpoint = Endpoint.of("some.host", 83).withIpAddr("127.0.0.3");
                final HAProxyConfig proxyConfig = (HAProxyConfig) selector.select(HTTP, endpoint);
                assertThat(proxyConfig.sourceAddress()).isNotNull();
                assertThat(proxyConfig.proxyAddress()).isNotNull();
                assertThat(proxiedAddresses.sourceAddress()).isEqualTo(proxyConfig.sourceAddress());
                assertThat(proxiedAddresses.destinationAddresses()).containsExactly(proxyConfig.proxyAddress());
            }
        }
    }

    @Test
    void testFirstProxiedAddressDestinationUsed() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ProxiedAddresses proxiedAddresses =
                ProxiedAddresses.of(new InetSocketAddress("127.0.0.1", 81),
                                    ImmutableList.of(new InetSocketAddress("127.0.0.2", 82),
                                                     new InetSocketAddress("127.0.0.3", 83)));
        final ServiceRequestContext serviceCtx =
                ServiceRequestContext.builder(req).proxiedAddresses(proxiedAddresses).build();

        try (SafeCloseable ignored = serviceCtx.push()) {
            final ClientRequestContext clientCtx = ClientRequestContext.builder(req).build();
            try (SafeCloseable ignored2 = clientCtx.push()) {
                final Endpoint endpoint = Endpoint.of("some.host", 84).withIpAddr("127.0.0.4");
                final HAProxyConfig proxyConfig = (HAProxyConfig) selector.select(HTTP, endpoint);
                assertThat(proxyConfig.sourceAddress()).isNotNull();
                assertThat(proxyConfig.proxyAddress()).isNotNull();
                assertThat(proxiedAddresses.sourceAddress()).isEqualTo(proxyConfig.sourceAddress());
                assertThat(proxiedAddresses.destinationAddresses()).startsWith(proxyConfig.proxyAddress());
            }
        }
    }
}
