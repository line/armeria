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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ProxyBuilder.ConnectProxyBuilder;
import com.linecorp.armeria.client.ProxyBuilder.Socks4ProxyBuilder;
import com.linecorp.armeria.client.ProxyBuilder.Socks5ProxyBuilder;

/**
 * TODO: Update javadoc.
 */
public class Proxy {

    private static final long USE_DEFAULT_TIMEOUT_MILLIS = -1;
    static final Proxy DEFAULT =
            new Proxy(ProxyType.NONE, new InetSocketAddress(0), USE_DEFAULT_TIMEOUT_MILLIS);

    private final ProxyType proxyType;
    private final InetSocketAddress proxyAddress;
    private final long connectTimeoutMillis;

    @Nullable
    private String userName;

    Proxy(ProxyType proxyType, InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        this.proxyType = proxyType;
        this.proxyAddress = proxyAddress;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    /**
     * TODO: Update javadoc.
     */
    public static Socks4ProxyBuilder socks4(InetSocketAddress proxyAddress) {
        return new Socks4ProxyBuilder(requireNonNull(proxyAddress), USE_DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * TODO: Update javadoc.
     */
    public static Socks4ProxyBuilder socks4(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis >= 0);
        return new Socks4ProxyBuilder(requireNonNull(proxyAddress), connectTimeoutMillis);
    }

    /**
     * TODO: Update javadoc.
     */
    public static Socks5ProxyBuilder socks5(InetSocketAddress proxyAddress) {
        return new Socks5ProxyBuilder(requireNonNull(proxyAddress), USE_DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * TODO: Update javadoc.
     */
    public static Socks5ProxyBuilder socks5(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis >= 0);
        return new Socks5ProxyBuilder(requireNonNull(proxyAddress), connectTimeoutMillis);
    }

    /**
     * TODO: Update javadoc.
     */
    public static ConnectProxyBuilder connect(InetSocketAddress proxyAddress) {
        return new ConnectProxyBuilder(requireNonNull(proxyAddress), USE_DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * TODO: Update javadoc.
     */
    public static ConnectProxyBuilder connect(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis >= 0);
        return new ConnectProxyBuilder(requireNonNull(proxyAddress), connectTimeoutMillis);
    }

    ProxyType proxyType() {
        return proxyType;
    }

    /**
     * TODO: Update javadoc.
     */
    public InetSocketAddress proxyAddress() {
        return proxyAddress;
    }

    /**
     * TODO: Update javadoc.
     */
    public long connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * TODO: Update javadoc.
     */
    @Nullable
    public String userName() {
        return userName;
    }

    void setUserName(@Nullable String userName) {
        this.userName = userName;
    }

    enum ProxyType {
        NONE,
        SOCKS4,
        SOCKS5,
        CONNECT,
    }

    /**
     * TODO: Update javadoc.
     */
    public static class Socks4Proxy extends Proxy {
        Socks4Proxy(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(ProxyType.SOCKS4, proxyAddress, connectTimeoutMillis);
        }
    }

    /**
     * TODO: Update javadoc.
     */
    public static class Socks5Proxy extends Proxy {
        @Nullable
        private String password;

        Socks5Proxy(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(ProxyType.SOCKS5, proxyAddress, connectTimeoutMillis);
        }

        /**
         * TODO: Update javadoc.
         */
        @Nullable
        public String password() {
            return password;
        }

        void setPassword(@Nullable String password) {
            this.password = password;
        }
    }

    /**
     * TODO: Update javadoc.
     */
    public static class ConnectProxy extends Proxy {
        @Nullable
        private String password;

        ConnectProxy(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(ProxyType.CONNECT, proxyAddress, connectTimeoutMillis);
        }

        /**
         * TODO: Update javadoc.
         */
        @Nullable
        public String password() {
            return password;
        }

        void setPassword(@Nullable String password) {
            this.password = password;
        }
    }
}
