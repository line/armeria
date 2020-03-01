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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

/**
 * TODO: Update javadoc.
 */
public final class Proxy {

    private static final long USE_DEFAULT_TIMEOUT_MILLIS = -1;
    static final Proxy DEFAULT = new Proxy(ProxyType.NONE, new InetSocketAddress(0));

    private final ProxyType proxyType;
    private final InetSocketAddress proxyAddress;
    private final long connectTimeoutMillis;

    private Proxy(ProxyType proxyType, InetSocketAddress proxyAddress) {
        this.proxyType = proxyType;
        this.proxyAddress = proxyAddress;
        connectTimeoutMillis = USE_DEFAULT_TIMEOUT_MILLIS;
    }

    private Proxy(ProxyType proxyType, InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        this.proxyType = proxyType;
        this.proxyAddress = proxyAddress;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy socks4(InetSocketAddress proxyAddress) {
        return new Proxy(ProxyType.SOCKS4, requireNonNull(proxyAddress));
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy socks4(InetSocketAddress proxyAddress, long connectionTimeoutMillis) {
        checkArgument(connectionTimeoutMillis > 0);
        return new Proxy(ProxyType.SOCKS4, requireNonNull(proxyAddress), connectionTimeoutMillis);
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy socks5(InetSocketAddress proxyAddress) {
        return new Proxy(ProxyType.SOCKS5, requireNonNull(proxyAddress));
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy socks5(InetSocketAddress proxyAddress, long connectionTimeoutMillis) {
        checkArgument(connectionTimeoutMillis > 0);
        return new Proxy(ProxyType.SOCKS5, requireNonNull(proxyAddress), connectionTimeoutMillis);
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy connect(InetSocketAddress proxyAddress) {
        return new Proxy(ProxyType.CONNECT, requireNonNull(proxyAddress));
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy connect(InetSocketAddress proxyAddress, long connectionTimeoutMillis) {
        checkArgument(connectionTimeoutMillis > 0);
        return new Proxy(ProxyType.CONNECT, requireNonNull(proxyAddress), connectionTimeoutMillis);
    }

    ProxyType getProxyType() {
        return proxyType;
    }

    /**
     * TODO: Update javadoc.
     */
    public InetSocketAddress getProxyAddress() {
        return proxyAddress;
    }

    /**
     * TODO: Update javadoc.
     */
    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    enum ProxyType {
        NONE,
        SOCKS4,
        SOCKS5,
        CONNECT,
    }
}
