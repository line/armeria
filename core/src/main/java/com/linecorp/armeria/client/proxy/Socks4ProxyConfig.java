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
 * SOCKS4 proxy configuration.
 */
public final class Socks4ProxyConfig extends ProxyConfig {

    private final InetSocketAddress proxyAddress;

    @Nullable
    private final String username;

    Socks4ProxyConfig(InetSocketAddress proxyAddress, @Nullable String username) {
        this.proxyAddress = proxyAddress;
        this.username = username;
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

    @Override
    public ProxyType proxyType() {
        return ProxyType.SOCKS4;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Socks4ProxyConfig)) {
            return false;
        }
        final Socks4ProxyConfig that = (Socks4ProxyConfig) o;
        return proxyAddress.equals(that.proxyAddress) &&
               Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proxyAddress, username);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("proxyType", proxyType())
                          .add("proxyAddress", proxyAddress())
                          .add("username", username())
                          .toString();
    }
}
