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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;

public class ProxyConfigTest {

    @ParameterizedTest
    @ArgumentsSource(ProxyConfigProvider.class)
    void testEqualityAndHashCode(ProxyConfig config) {
        final List<ProxyConfig> equalProxyConfigs =
                getValidProxyConfigs().stream().filter(config::equals).collect(Collectors.toList());
        assertThat(equalProxyConfigs).hasSize(1);
        assertThat(equalProxyConfigs.get(0).hashCode()).isEqualTo(config.hashCode());
    }

    @Test
    void testUnresolvedProxyAddress() {
        final InetSocketAddress unresolved = InetSocketAddress.createUnresolved("unresolved", 0);
        final InetSocketAddress resolved = new InetSocketAddress("127.0.0.1", 80);
        assertThatThrownBy(() -> ProxyConfig.socks4(unresolved)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyConfig.socks5(unresolved)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyConfig.connect(unresolved)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyConfig.haproxy(unresolved, resolved))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyConfig.haproxy(resolved, unresolved))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNullProxyAddresss() {
        assertThatThrownBy(() -> ProxyConfig.socks4(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProxyConfig.socks5(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProxyConfig.connect(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProxyConfig.haproxy(null, null)).isInstanceOf(NullPointerException.class);
    }

    private static class ProxyConfigProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return getValidProxyConfigs().stream().map(Arguments::of);
        }
    }

    private static List<ProxyConfig> getValidProxyConfigs() {
        final InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 8080);
        final InetSocketAddress addr2 = new InetSocketAddress("127.0.0.1", 8081);
        return ImmutableList.of(
                ProxyConfig.direct(),

                ProxyConfig.connect(addr1),
                ProxyConfig.connect(addr2),
                ProxyConfig.connect(addr1, true),
                ProxyConfig.connect(addr1, "uname1", "pw1", false),
                ProxyConfig.connect(addr1, "uname2", "pw1", false),
                ProxyConfig.connect(addr1, "uname1", "pw2", false),
                ProxyConfig.connect(addr1, "uname1", "pw1", true),

                ProxyConfig.socks4(addr1),
                ProxyConfig.socks4(addr2),
                ProxyConfig.socks4(addr1, "uname1"),
                ProxyConfig.socks4(addr1, "uname2"),

                ProxyConfig.socks5(addr1),
                ProxyConfig.socks5(addr2),
                ProxyConfig.socks5(addr1, "uname1", "pw1"),
                ProxyConfig.socks5(addr1, "uname2", "pw1"),
                ProxyConfig.socks5(addr1, "uname1", "pw2"),

                ProxyConfig.haproxy(addr1, addr2),
                ProxyConfig.haproxy(addr2, addr1),
                new HAProxyConfig(addr1)
        );
    }
}
