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

import static com.linecorp.armeria.client.proxy.DirectProxyConfig.DIRECT_PROXY_CONFIG;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.Proxy;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;

/**
 * Base configuration for proxy settings used by {@link ClientFactory}.
 */
public abstract class ProxyConfig {
    private static final Logger logger = LoggerFactory.getLogger(ProxyConfig.class);

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress) {
        return new Socks4ProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), null);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress, String username) {
        return new Socks4ProxyConfig(requireNonNull(proxyAddress, "proxyAddress"),
                                     requireNonNull(username, "username"));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static Socks5ProxyConfig socks5(InetSocketAddress proxyAddress) {
        return new Socks5ProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), null, null);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     */
    public static Socks5ProxyConfig socks5(
            InetSocketAddress proxyAddress, String username, String password) {
        return new Socks5ProxyConfig(
                requireNonNull(proxyAddress, "proxyAddress"), requireNonNull(username, "username"),
                requireNonNull(password, "password"));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress) {
        return new ConnectProxyConfig(requireNonNull(proxyAddress, "proxyAddress"), null, null, false);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param useTls whether to use TLS to connect to the proxy
     */
    public static ConnectProxyConfig connect(
            InetSocketAddress proxyAddress, boolean useTls) {
        return new ConnectProxyConfig(
                requireNonNull(proxyAddress, "proxyAddress"), null, null, useTls);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     * @param useTls whether to use TLS to connect to the proxy
     */
    public static ConnectProxyConfig connect(
            InetSocketAddress proxyAddress, String username, String password, boolean useTls) {
        return new ConnectProxyConfig(
                requireNonNull(proxyAddress, "proxyAddress"), requireNonNull(username, "username"),
                requireNonNull(password, "password"), useTls);
    }

    /**
     * Returns a {@code ProxyConfig} which signifies that a proxy is absent.
     */
    public static ProxyConfig direct() {
        return DIRECT_PROXY_CONFIG;
    }

    /**
     * TODO: add javadocs.
     * FIXME: find a way to hide this.
     */
    public static ProxyConfig fromProxy(Proxy proxy) {
        if (!(proxy.address() instanceof InetSocketAddress)) {
            logger.warn("invalid proxy address for: {}", proxy);
            return direct();
        }

        switch (proxy.type()) {
            case HTTP:
                return connect((InetSocketAddress) proxy.address());
            case SOCKS:
                // TODO: find a way to use flag "socksProxyVersion"
                return socks5((InetSocketAddress) proxy.address());
            case DIRECT:
            default:
                return direct();
        }
    }

    ProxyConfig() {
    }

    /**
     * Returns the proxy type.
     */
    public abstract ProxyType proxyType();

    @Nullable
    static String maskPassword(@Nullable String username, @Nullable String password) {
        return username != null ? "****" : null;
    }
}
