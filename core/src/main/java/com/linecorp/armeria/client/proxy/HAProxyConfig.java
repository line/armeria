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
 * HAPROXY proxy configuration.
 */
public final class HAProxyConfig extends ProxyConfig {

    @Nullable
    private InetSocketAddress srcAddress;

    private final InetSocketAddress destAddress;

    HAProxyConfig(InetSocketAddress destAddress) {
        this.destAddress = destAddress;
    }

    HAProxyConfig(InetSocketAddress srcAddress, InetSocketAddress destAddress) {
        checkArgument(srcAddress.getAddress().getClass() == destAddress.getAddress().getClass(),
                      "srcAddress and destAddress should be the same type");
        this.srcAddress = srcAddress;
        this.destAddress = destAddress;
    }

    @Override
    public InetSocketAddress proxyAddress() {
        return destAddress;
    }

    @Override
    public ProxyType proxyType() {
        return ProxyType.HAPROXY;
    }

    /**
     * TBU.
     */
    @Nullable
    public InetSocketAddress srcAddress() {
        return srcAddress;
    }

    /**
     * TBU.
     */
    public InetSocketAddress destAddress() {
        return destAddress;
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
        return Objects.equals(srcAddress, that.srcAddress) &&
               destAddress == that.destAddress;
    }

    @Override
    public int hashCode() {
        return Objects.hash(srcAddress, destAddress);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyType", proxyType())
                          .add("srcAddress", srcAddress)
                          .add("destAddress", destAddress)
                          .omitNullValues()
                          .toString();
    }
}
