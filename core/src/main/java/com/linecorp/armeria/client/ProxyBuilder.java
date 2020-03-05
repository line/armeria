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

import com.linecorp.armeria.client.Proxy.ConnectProxy;
import com.linecorp.armeria.client.Proxy.Socks4Proxy;
import com.linecorp.armeria.client.Proxy.Socks5Proxy;

abstract class ProxyBuilder {
    @Nullable
    private String userName;
    @Nullable
    private String password;

    private final InetSocketAddress proxyAddress;
    private final long connectTimeoutMillis;
    private boolean useSsl;

    private ProxyBuilder(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
        this.proxyAddress = proxyAddress;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Nullable
    protected String getUserName() {
        return userName;
    }

    protected void setUserName(@Nullable String userName) {
        this.userName = userName;
    }

    @Nullable
    protected String getPassword() {
        return password;
    }

    protected void setPassword(@Nullable String password) {
        this.password = password;
    }

    protected InetSocketAddress getProxyAddress() {
        return proxyAddress;
    }

    protected long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    protected void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    protected boolean getUseSsl() {
        return useSsl;
    }

    /**
     * Build the proxy object.
     */
    public abstract Proxy build();

    /**
     * A {@code ProxyBuilder} which builds a SOCKS4 protocol configuration.
     */
    public static final class Socks4ProxyBuilder extends ProxyBuilder {
        Socks4ProxyBuilder(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(proxyAddress, connectTimeoutMillis);
        }

        /**
         * Set the proxy userName.
         */
        public Socks4ProxyBuilder userName(String userName) {
            setUserName(userName);
            return this;
        }

        @Override
        public Socks4Proxy build() {
            final Socks4Proxy socks4Proxy =
                    new Socks4Proxy(getProxyAddress(), getConnectTimeoutMillis());
            socks4Proxy.setUserName(getUserName());
            return socks4Proxy;
        }
    }

    /**
     * A {@code ProxyBuilder} which builds a SOCKS4 protocol configuration.
     */
    public static final class Socks5ProxyBuilder extends ProxyBuilder {
        Socks5ProxyBuilder(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(proxyAddress, connectTimeoutMillis);
        }

        /**
         * Set the proxy userName.
         */
        public Socks5ProxyBuilder userName(String userName) {
            setUserName(userName);
            return this;
        }

        /**
         * Set the proxy password.
         */
        public Socks5ProxyBuilder password(String password) {
            setPassword(password);
            return this;
        }

        @Override
        public Socks5Proxy build() {
            final Socks5Proxy socks5Proxy =
                    new Socks5Proxy(getProxyAddress(), getConnectTimeoutMillis());
            socks5Proxy.setUserName(getUserName());
            socks5Proxy.setPassword(getPassword());
            return socks5Proxy;
        }
    }

    /**
     * A {@code ProxyBuilder} which builds a SOCKS4 protocol configuration.
     */
    public static final class ConnectProxyBuilder extends ProxyBuilder {
        ConnectProxyBuilder(InetSocketAddress proxyAddress, long connectTimeoutMillis) {
            super(proxyAddress, connectTimeoutMillis);
        }

        /**
         * Set the proxy userName and password.
         */
        public ConnectProxyBuilder auth(String userName, String password) {
            setUserName(requireNonNull(userName));
            setPassword(requireNonNull(password));
            return this;
        }

        /**
         * Enables ssl for the proxy.
         */
        public ConnectProxyBuilder useSsl(boolean useSsl) {
            setUseSsl(useSsl);
            return this;
        }

        @Override
        public ConnectProxy build() {
            final ConnectProxy connectProxy =
                    new ConnectProxy(getProxyAddress(), getConnectTimeoutMillis());
            connectProxy.setUserName(getUserName());
            connectProxy.setPassword(getPassword());
            connectProxy.setUseSsl(getUseSsl());
            return connectProxy;
        }
    }
}
