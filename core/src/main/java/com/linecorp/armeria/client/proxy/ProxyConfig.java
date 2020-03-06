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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.client.ClientFactory;

/**
 * Contains base configuration for proxy related settings used in {@link ClientFactory}.
 */
public class ProxyConfig {

    static final ProxyConfig NONE_CONFIG = new ProxyConfig(new InetSocketAddress(0));

    private final InetSocketAddress proxyAddress;

    ProxyConfig(InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks4ProxyBuilder socks4(InetSocketAddress proxyAddress) {
        return new Socks4ProxyBuilder(requireNonNull(proxyAddress));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks5ProxyBuilder socks5(InetSocketAddress proxyAddress) {
        return new Socks5ProxyBuilder(requireNonNull(proxyAddress));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     * @param proxyAddress The proxy address.
     */
    public static ConnectProxyBuilder connect(InetSocketAddress proxyAddress) {
        return new ConnectProxyBuilder(requireNonNull(proxyAddress));
    }

    /**
     * The proxy address.
     */
    public InetSocketAddress proxyAddress() {
        return proxyAddress;
    }

    /**
     * A configuration which signifies proxy should be disabled.
     */
    public static ProxyConfig none() {
        return NONE_CONFIG;
    }
}
