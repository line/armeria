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

import static com.google.common.base.Preconditions.checkArgument;

import java.net.InetSocketAddress;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * <a href="http://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">HAPROXY configuration.</a>
 */
public final class HAProxyConfig extends ProxyConfig {

    private final InetSocketAddress proxyAddress;

    @Nullable
    private final InetSocketAddress sourceAddress;

    HAProxyConfig(InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
        sourceAddress = null;
    }

    HAProxyConfig(InetSocketAddress proxyAddress, InetSocketAddress sourceAddress) {
        checkArgument(sourceAddress.getAddress().getClass() == proxyAddress.getAddress().getClass(),
                      "sourceAddress and proxyAddress should be the same type");
        this.proxyAddress = proxyAddress;
        this.sourceAddress = sourceAddress;
    }

    @Override
    public ProxyType proxyType() {
        return ProxyType.HAPROXY;
    }

    @Override
    public InetSocketAddress proxyAddress() {
        return proxyAddress;
    }

    /**
     * Represents the source address. When this value is {@code null}, it will be inferred
     * from either the {@link ServiceRequestContext} or the local connection address.
     */
    @Nullable
    public InetSocketAddress sourceAddress() {
        return sourceAddress;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HAProxyConfig)) {
            return false;
        }
        final HAProxyConfig that = (HAProxyConfig) o;
        return proxyAddress.equals(that.proxyAddress) &&
               Objects.equals(sourceAddress, that.sourceAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proxyAddress, sourceAddress);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("proxyType", proxyType())
                          .add("proxyAddress", proxyAddress)
                          .add("sourceAddress", sourceAddress)
                          .toString();
    }
}
