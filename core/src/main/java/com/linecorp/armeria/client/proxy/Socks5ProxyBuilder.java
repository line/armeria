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

import java.net.InetSocketAddress;

/**
 * A {@code AbstractProxyBuilder} which builds a SOCKS5 protocol configuration.
 */
public final class Socks5ProxyBuilder extends AbstractProxyBuilder {

    Socks5ProxyBuilder(InetSocketAddress proxyAddress) {
        super(proxyAddress);
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
    public Socks5ProxyConfig build() {
        return new Socks5ProxyConfig(getProxyAddress(), getUserName(), getPassword());
    }
}
