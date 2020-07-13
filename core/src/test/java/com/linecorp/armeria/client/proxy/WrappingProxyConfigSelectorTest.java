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

import static com.linecorp.armeria.common.SerializationFormat.NONE;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;

public class WrappingProxyConfigSelectorTest {

    @Test
    void testSelectUsesFirstProxy() {
        final Proxy proxy1 = new Proxy(Type.HTTP, new InetSocketAddress(80));
        final ProxyConfigSelector selector = ProxyConfigSelector.of(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return ImmutableList.of(
                        proxy1,
                        Proxy.NO_PROXY,
                        new Proxy(Type.SOCKS, new InetSocketAddress(1080))
                );
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        final ProxyConfig proxyConfig = selector.select(HTTP, Endpoint.of("127.0.0.1"));
        assertThat(proxyConfig.proxyType()).isEqualTo(ProxyType.CONNECT);
        assertThat(proxyConfig.proxyAddress()).isEqualTo(proxy1.address());
    }

    @Test
    void testSelectNullProxyReturnsDirect() {
        ProxyConfigSelector selector = ProxyConfigSelector.of(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return null;
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        ProxyConfig proxyConfig = selector.select(HTTP, Endpoint.of("127.0.0.1"));
        assertThat(proxyConfig).isEqualTo(ProxyConfig.direct());

        selector = ProxyConfigSelector.of(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                final ArrayList<Proxy> list = new ArrayList<>();
                list.add(null);
                return list;
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        proxyConfig = selector.select(HTTP, Endpoint.of("127.0.0.1"));
        assertThat(proxyConfig).isEqualTo(ProxyConfig.direct());
    }

    @Test
    void testSelectUnresolvedAddress() {
        final ProxyConfigSelector selector = ProxyConfigSelector.of(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return ImmutableList.of(new Proxy(
                        Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 1080)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        final ProxyConfig proxyConfig = selector.select(HTTP, Endpoint.of("127.0.0.1"));
        assertThat(proxyConfig.proxyType()).isEqualTo(ProxyType.CONNECT);

        final InetSocketAddress proxyAddress = proxyConfig.proxyAddress();
        assertThat(proxyAddress).isNotNull();
        assertThat(proxyAddress.isUnresolved()).isFalse();
        assertThat(proxyAddress.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(proxyAddress.getPort()).isEqualTo(1080);
    }

    @Test
    void testConnectFailedInvoked() throws URISyntaxException {
        final Endpoint endpoint = Endpoint.of("127.0.0.1", 80);
        final SocketAddress socketAddress = new InetSocketAddress(81);
        final RuntimeException exception = new RuntimeException("expected");

        final ProxyConfigSelector selector = ProxyConfigSelector.of(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return ImmutableList.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                assertThat(uri).hasHost(endpoint.host())
                               .hasPort(endpoint.port())
                               .hasScheme(Scheme.of(NONE, H1C).uriText());
                assertThat(sa).isEqualTo(socketAddress);
                assertThat(ioe).hasCause(exception);
            }
        });

        selector.connectFailed(H1C, endpoint, socketAddress, exception);
    }

    @ParameterizedTest
    @EnumSource(SessionProtocol.class)
    void testProxySchemeForSessionProtocols(SessionProtocol sessionProtocol) {
        final Proxy proxy = new Proxy(Type.HTTP, new InetSocketAddress(80));
        final ProxyConfigSelector selector = ProxyConfigSelector.of(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                assertThat(uri.getScheme()).isEqualTo(sessionProtocol.uriText());
                return ImmutableList.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        final ProxyConfig proxyConfig = selector.select(sessionProtocol, Endpoint.of("127.0.0.1"));
        assertThat(proxyConfig.proxyType()).isEqualTo(ProxyType.CONNECT);
        assertThat(proxyConfig.proxyAddress()).isEqualTo(proxy.address());
    }
}
