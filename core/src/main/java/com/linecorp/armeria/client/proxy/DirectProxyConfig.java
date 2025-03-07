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

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Represents a direct connection without a proxy.
 */
public final class DirectProxyConfig extends ProxyConfig {

    static final DirectProxyConfig DIRECT_PROXY_CONFIG = new DirectProxyConfig();

    private DirectProxyConfig() {}

    @Override
    public ProxyType proxyType() {
        return ProxyType.DIRECT;
    }

    @Nullable
    @Override
    public InetSocketAddress proxyAddress() {
        return null;
    }

    @Override
    public ProxyConfig withProxyAddress(InetSocketAddress newProxyAddress) {
        throw new UnsupportedOperationException(
                "A proxy address can't be set to DirectProxyConfig.");
    }

    @Override
    public String toString() {
        return "DirectProxyConfig{proxyType=DIRECT}";
    }
}
