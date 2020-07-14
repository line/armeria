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
import java.util.Objects;

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

    @Override
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
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConnectProxyConfig)) {
            return false;
        }
        final ConnectProxyConfig that = (ConnectProxyConfig) o;
        return useTls == that.useTls &&
               proxyAddress.equals(that.proxyAddress) &&
               Objects.equals(username, that.username) &&
               Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proxyAddress, username, password, useTls);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyType", proxyType())
                          .add("proxyAddress", proxyAddress())
                          .add("username", username())
                          .add("password", maskPassword(username(), password()))
                          .add("useTls", useTls())
                          .omitNullValues()
                          .toString();
    }
}
