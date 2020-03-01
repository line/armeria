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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Proxy.ProxyType;

abstract class ProxyBuilder {
    @Nullable
    private String userName;
    @Nullable
    private String password;

    private final ProxyType proxyType;
    private final InetSocketAddress proxyAddress;
    private final long connectTimeoutMillis;

    private ProxyBuilder(ProxyType proxyType, InetSocketAddress proxyAddress,
                         long connectTimeoutMillis) {
        this.proxyType = proxyType;
        this.proxyAddress = proxyAddress;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    protected void setUserName(@Nullable String userName) {
        this.userName = userName;
    }

    protected void setPassword(@Nullable String password) {
        this.password = password;
    }

    /**
     * TODO: Update javadoc.
     */
    public Proxy build() {
        final Proxy proxy = new Proxy(proxyType, proxyAddress, connectTimeoutMillis);
        proxy.setUserName(userName);
        proxy.setPassword(password);
        return proxy;
    }

    /**
     * TODO: Update javadoc.
     */
    public static final class Socks4ProxyBuilder extends ProxyBuilder {
        Socks4ProxyBuilder(InetSocketAddress proxyAddress,
                                   long connectTimeoutMillis) {
            super(ProxyType.SOCKS4, proxyAddress, connectTimeoutMillis);
        }

        /**
         * TODO: Update javadoc.
         */
        public Socks4ProxyBuilder userName(String userName) {
            setUserName(userName);
            return this;
        }
    }

    /**
     * TODO: Update javadoc.
     */
    public static final class Socks5ProxyBuilder extends ProxyBuilder {
        Socks5ProxyBuilder(InetSocketAddress proxyAddress,
                                   long connectTimeoutMillis) {
            super(ProxyType.SOCKS5, proxyAddress, connectTimeoutMillis);
        }

        /**
         * TODO: Update javadoc.
         */
        public Socks5ProxyBuilder userName(String userName) {
            setUserName(userName);
            return this;
        }

        /**
         * TODO: Update javadoc.
         */
        public Socks5ProxyBuilder password(String password) {
            setPassword(password);
            return this;
        }
    }

    /**
     * TODO: Update javadoc.
     */
    public static final class ConnectProxyBuilder extends ProxyBuilder {
        ConnectProxyBuilder(InetSocketAddress proxyAddress,
                                    long connectTimeoutMillis) {
            super(ProxyType.CONNECT, proxyAddress, connectTimeoutMillis);
        }

        /**
         * TODO: Update javadoc.
         */
        public ConnectProxyBuilder auth(String userName, String password) {
            setUserName(requireNonNull(userName));
            setPassword(requireNonNull(password));
            return this;
        }
    }
}
