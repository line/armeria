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
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

/**
 * TODO: Update javadoc.
 */
public final class Proxy {

    private static final long DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10000;
    private final ProxyType proxyType;
    private final InetSocketAddress proxyAddress;
    private final long connectionTimeoutMillis;

    /**
     * TODO: Update javadoc.
     */
    public static final Proxy DEFAULT = new Proxy(ProxyType.NONE, null);

    private Proxy(ProxyType proxyType, InetSocketAddress proxyAddress) {
        this.proxyType = proxyType;
        this.proxyAddress = proxyAddress;
        connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT_MILLIS;
    }

    private Proxy(ProxyType proxyType, InetSocketAddress proxyAddress, long connectionTimeoutMillis) {
        this.proxyType = proxyType;
        this.proxyAddress = proxyAddress;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy of(ProxyType proxyType, InetSocketAddress proxyAddress) {
        return new Proxy(requireNonNull(proxyType), requireNonNull(proxyAddress));
    }

    /**
     * TODO: Update javadoc.
     */
    public static Proxy of(ProxyType proxyType, InetSocketAddress proxyAddress,
                           long connectionTimeoutMillis) {
        checkArgument(connectionTimeoutMillis > 0);
        return new Proxy(requireNonNull(proxyType), requireNonNull(proxyAddress), connectionTimeoutMillis);
    }

    /**
     * TODO: Update javadoc.
     */
    public ProxyType getProxyType() {
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
    public long getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }
}
