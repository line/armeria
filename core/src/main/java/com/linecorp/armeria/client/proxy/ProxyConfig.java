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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.proxy.DirectProxyConfig.DIRECT_PROXY_CONFIG;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Base configuration for proxy settings used by {@link ClientFactory}.
 */
public abstract class ProxyConfig {

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks4ProxyConfig(proxyAddress, null);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress, String username) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks4ProxyConfig(proxyAddress, requireNonNull(username, "username"));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static Socks5ProxyConfig socks5(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks5ProxyConfig(proxyAddress, null, null);
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
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks5ProxyConfig(proxyAddress, requireNonNull(username, "username"),
                                     requireNonNull(password, "password"));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, false);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param useTls whether to use TLS to connect to the proxy
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress, boolean useTls) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, useTls);
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
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, requireNonNull(username, "username"),
                                      requireNonNull(password, "password"), useTls);
    }

    /**
     * Creates a {@link ProxyConfig} configuration for HAProxy protocol.
     *
     * @param proxyAddress the proxy address
     * @param sourceAddress the source address
     */
    public static HAProxyConfig haproxy(
            InetSocketAddress proxyAddress, InetSocketAddress sourceAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        requireNonNull(sourceAddress, "sourceAddress");
        checkArgument(!sourceAddress.isUnresolved(), "sourceAddress must be resolved");
        return new HAProxyConfig(proxyAddress, sourceAddress);
    }

    /**
     * Creates a {@link ProxyConfig} configuration for HAProxy protocol. The {@code sourceAddress} will
     * be inferred from either the {@link ServiceRequestContext} or the local connection address.
     *
     * @param proxyAddress the proxy address
     */
    public static ProxyConfig haproxy(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new HAProxyConfig(proxyAddress);
    }

    /**
     * Returns a {@code ProxyConfig} which signifies that a proxy is absent.
     */
    public static ProxyConfig direct() {
        return DIRECT_PROXY_CONFIG;
    }

    ProxyConfig() {
    }

    /**
     * Returns the proxy type.
     */
    public abstract ProxyType proxyType();

    /**
     * Returns the proxy address which is {@code null} only for {@link ProxyType#DIRECT}.
     */
    @Nullable
    public abstract InetSocketAddress proxyAddress();

    @Nullable
    static String maskPassword(@Nullable String username, @Nullable String password) {
        return username != null ? "****" : null;
    }
}
