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

import static com.linecorp.armeria.client.proxy.NoopProxyConfig.NOOP_PROXY_CONFIG;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientFactory;

/**
 * Contains base configuration for proxy related settings used in {@link ClientFactory}.
 */
public abstract class ProxyConfig {

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks4ProxyConfigBuilder socks4(InetSocketAddress proxyAddress) {
        return new Socks4ProxyConfigBuilder(requireNonNull(proxyAddress));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks5ProxyConfigBuilder socks5(InetSocketAddress proxyAddress) {
        return new Socks5ProxyConfigBuilder(requireNonNull(proxyAddress));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     * @param proxyAddress The proxy address.
     */
    public static ConnectProxyConfigBuilder connect(InetSocketAddress proxyAddress) {
        return new ConnectProxyConfigBuilder(requireNonNull(proxyAddress));
    }

    /**
     * Returns a {@code ProxyConfig} which signifies that proxy is disabled.
     */
    public static ProxyConfig noop() {
        return NOOP_PROXY_CONFIG;
    }

    private final InetSocketAddress proxyAddress;

    @Nullable
    private final String username;

    ProxyConfig(InetSocketAddress proxyAddress, @Nullable String username) {
        this.proxyAddress = proxyAddress;
        this.username = username;
    }

    /**
     * The proxy address.
     */
    public InetSocketAddress proxyAddress() {
        return proxyAddress;
    }

    /**
     * The configured username.
     */
    @Nullable
    public String username() {
        return username;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyAddress", proxyAddress())
                          .add("username", username())
                          .toString();
    }
}
