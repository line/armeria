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

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * CONNECT proxy configuration.
 */
public final class ConnectProxyConfig extends ProxyConfig {

    private final InetSocketAddress proxyAddress;

    @Nullable
    private final String username;

    @Nullable
    private final String password;

    private final boolean useTls;

    ConnectProxyConfig(InetSocketAddress proxyAddress, @Nullable String username,
                       @Nullable String password, boolean useTls) {
        this.proxyAddress = proxyAddress;
        this.username = username;
        this.password = password;
        this.useTls = useTls;
    }

    /**
     * Returns the configured proxy address.
     */
    public InetSocketAddress proxyAddress() {
        return proxyAddress;
    }

    /**
     * Returns the configured username.
     */
    @Nullable
    public String username() {
        return username;
    }

    /**
     * Returns the configured password.
     */
    @Nullable
    public String password() {
        return password;
    }

    /**
     * Returns whether ssl is enabled.
     */
    public boolean useTls() {
        return useTls;
    }

    @Override
    public ProxyType proxyType() {
        return ProxyType.CONNECT;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyType", proxyType())
                          .add("proxyAddress", proxyAddress())
                          .add("username", username())
                          .add("password", maskedStr(password()))
                          .add("useTls", useTls())
                          .toString();
    }
}
