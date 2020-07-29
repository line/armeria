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

/**
 * <a href="http://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">HAPROXY configuration.</a>
 */
public final class HAProxyConfig extends ProxyConfig {

    @Nullable
    private final InetSocketAddress sourceAddress;

    private final InetSocketAddress destinationAddress;

    HAProxyConfig(InetSocketAddress destinationAddress) {
        sourceAddress = null;
        this.destinationAddress = destinationAddress;
    }

    HAProxyConfig(InetSocketAddress sourceAddress, InetSocketAddress destinationAddress) {
        checkArgument(sourceAddress.getAddress().getClass() == destinationAddress.getAddress().getClass(),
                      "sourceAddress and destinationAddress should be the same type");
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
    }

    /**
     * Represents the destination address for the HAProxy protocol.
     */
    @Override
    public InetSocketAddress proxyAddress() {
        return destinationAddress;
    }

    @Override
    public ProxyType proxyType() {
        return ProxyType.HAPROXY;
    }

    /**
     * Represents the source address. The local connection address will be used
     * if this value is {@code null}.
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
        return Objects.equals(sourceAddress, that.sourceAddress) &&
               destinationAddress.equals(that.destinationAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceAddress, destinationAddress);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyType", proxyType())
                          .add("sourceAddress", sourceAddress)
                          .add("destinationAddress", destinationAddress)
                          .omitNullValues()
                          .toString();
    }
}
