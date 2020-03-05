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
 * Contains base configuration for proxy related settings used in {@link ClientFactory}.
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
     * Creates a {@code Proxy} configuration for SOCKS4 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks4ProxyBuilder socks4(InetSocketAddress proxyAddress) {
        return new Socks4ProxyBuilder(requireNonNull(proxyAddress), USE_DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a {@code Proxy} configuration for SOCKS4 protocol.
     * @param proxyAddress The proxy address.
     * @param connectTimeoutMillis The connection timeout for connecting to the proxy server.
     */
    public static Socks4ProxyBuilder socks4(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis >= 0);
        return new Socks4ProxyBuilder(requireNonNull(proxyAddress), connectTimeoutMillis);
    }

    /**
     * Creates a {@code Proxy} configuration for SOCKS5 protocol.
     * @param proxyAddress The proxy address.
     */
    public static Socks5ProxyBuilder socks5(InetSocketAddress proxyAddress) {
        return new Socks5ProxyBuilder(requireNonNull(proxyAddress), USE_DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a {@code Proxy} configuration for SOCKS5 protocol.
     * @param proxyAddress The proxy address.
     * @param connectTimeoutMillis The connection timeout for connecting to the proxy server.
     */
    public static Socks5ProxyBuilder socks5(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis >= 0);
        return new Socks5ProxyBuilder(requireNonNull(proxyAddress), connectTimeoutMillis);
    }

    /**
     * Creates a {@code Proxy} configuration for CONNECT protocol.
     * @param proxyAddress The proxy address.
     */
    public static ConnectProxyBuilder connect(InetSocketAddress proxyAddress) {
        return new ConnectProxyBuilder(requireNonNull(proxyAddress), USE_DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a {@code Proxy} configuration for CONNECT protocol.
     * @param proxyAddress The proxy address.
     * @param connectTimeoutMillis The connection timeout for connecting to the proxy server.
     */
    public static ConnectProxyBuilder connect(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis >= 0);
        return new ConnectProxyBuilder(requireNonNull(proxyAddress), connectTimeoutMillis);
    }

    ProxyType proxyType() {
        return proxyType;
    }

    /**
     * The proxy address.
     */
    public InetSocketAddress proxyAddress() {
        return proxyAddress;
    }

    /**
     * The connect timeout.
     */
    public long connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * The userName.
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
     * Contains SOCKS4 proxy configuration used in {@link ClientFactory}.
     */
    public static class Socks4Proxy extends Proxy {
        Socks4Proxy(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(ProxyType.SOCKS4, proxyAddress, connectTimeoutMillis);
        }
    }

    /**
     * Contains SOCKS5 proxy configuration used in {@link ClientFactory}.
     */
    public static class Socks5Proxy extends Proxy {
        @Nullable
        private String password;

        Socks5Proxy(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(ProxyType.SOCKS5, proxyAddress, connectTimeoutMillis);
        }

        /**
         * The configured password.
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
     * Contains CONNECT proxy configuration used in {@link ClientFactory}.
     */
    public static class ConnectProxy extends Proxy {
        @Nullable
        private String password;

        private boolean useSsl;

        ConnectProxy(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(ProxyType.CONNECT, proxyAddress, connectTimeoutMillis);
        }

        /**
         * The configured password.
         */
        @Nullable
        public String password() {
            return password;
        }

        void setPassword(@Nullable String password) {
            this.password = password;
        }

        void setUseSsl(boolean useSsl) {
            this.useSsl = useSsl;
        }

        boolean getUseSsl() {
            return useSsl;
        }
    }
}
