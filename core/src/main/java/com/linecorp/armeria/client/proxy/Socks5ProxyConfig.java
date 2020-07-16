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
 * SOCKS5 proxy configuration.
 */
public final class Socks5ProxyConfig extends ProxyConfig {

    private final InetSocketAddress proxyAddress;

    @Nullable
    private final String username;

    @Nullable
    private final String password;

    Socks5ProxyConfig(InetSocketAddress proxyAddress, @Nullable String username,
                      @Nullable String password) {
        this.proxyAddress = proxyAddress;
        this.username = username;
        this.password = password;
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

    @Override
    public ProxyType proxyType() {
        return ProxyType.SOCKS5;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Socks5ProxyConfig that = (Socks5ProxyConfig) o;
        return proxyAddress.equals(that.proxyAddress) &&
               Objects.equals(username, that.username) &&
               Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proxyAddress, username, password);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyType", proxyType())
                          .add("proxyAddress", proxyAddress())
                          .add("username", username())
                          .add("password", maskPassword(username(), password()))
                          .omitNullValues()
                          .toString();
    }
}
