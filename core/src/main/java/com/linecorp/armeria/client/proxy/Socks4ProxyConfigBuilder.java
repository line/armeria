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
 * A {@link ProxyConfig} builder for the SOCKS4 protocol.
 */
public final class Socks4ProxyConfigBuilder extends AbstractProxyConfigBuilder {

    Socks4ProxyConfigBuilder(InetSocketAddress proxyAddress) {
        super(proxyAddress);
    }

    /**
     * Sets the proxy username.
     */
    public Socks4ProxyConfigBuilder username(String username) {
        setUsername(username);
        return this;
    }

    @Override
    public Socks4ProxyConfig build() {
        return new Socks4ProxyConfig(getProxyAddress(), getUsername());
    }
}
