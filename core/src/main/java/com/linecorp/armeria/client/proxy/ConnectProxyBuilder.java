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
 * A builder for a CONNECT protocol {@link ProxyConfig}.
 */
public final class ConnectProxyBuilder extends AbstractProxyBuilder {

    private boolean useSsl;

    ConnectProxyBuilder(InetSocketAddress proxyAddress) {
        super(proxyAddress);
    }

    /**
     * Sets the proxy username and password.
     */
    public ConnectProxyBuilder auth(String username, String password) {
        setUsername(requireNonNull(username));
        setPassword(requireNonNull(password));
        return this;
    }

    /**
     * Signifies whether to use ssl to connect to the proxy.
     * If enabled, the ssl configurations for the {@link ClientFactory} will also be applied to the proxy.
     */
    public ConnectProxyBuilder useSsl(boolean useSsl) {
        this.useSsl = useSsl;
        return this;
    }

    @Override
    public ConnectProxyConfig build() {
        return new ConnectProxyConfig(getProxyAddress(), getUsername(), getPassword(), useSsl);
    }
}
