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

import static com.linecorp.armeria.client.proxy.DisabledProxyConfig.DISABLED_PROXY_CONFIG;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientFactory;

/**
 * Contains base configuration for proxy related settings used in {@link ClientFactory}.
 */
public abstract class ProxyConfig {

    ProxyConfig() {
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress) {
        return new Socks4ProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), null);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     * @param proxyAddress The proxy address.
     * @param username The user name.
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress, @Nullable String username) {
        return new Socks4ProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), username);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks5ProxyConfig socks5(InetSocketAddress proxyAddress) {
        return new Socks5ProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), null, null);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     * @param proxyAddress The proxy address.
     * @param username The user name.
     * @param password The password.
     */
    public static Socks5ProxyConfig socks5(
            InetSocketAddress proxyAddress, @Nullable String username, @Nullable String password) {
        return new Socks5ProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), username, password);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     * @param proxyAddress The proxy address.
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress) {
        return new ConnectProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), null, null, false);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     * Username and password must both be null, or both be non-null.
     * @param proxyAddress The proxy address.
     * @param username The user name.
     * @param password The password.
     * @param useTls Whether to use TLS to connect to the proxy.
     */
    public static ConnectProxyConfig connect(
            InetSocketAddress proxyAddress, @Nullable String username, @Nullable String password,
            boolean useTls) {
        return new ConnectProxyConfig(requireNonNull(proxyAddress, "proxyAddress"),
                                      username, password, useTls);
    }

    /**
     * Returns a {@code ProxyConfig} which signifies that proxy is disabled.
     */
    public static ProxyConfig disabled() {
        return DISABLED_PROXY_CONFIG;
    }
}
