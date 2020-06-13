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

import java.net.SocketAddress;
import java.net.URI;

/**
 * A {@link ProxyConfigSelector} which selects a static {@link ProxyConfig}
 * for all requests.
 */
public final class StaticProxyConfigSelector implements ProxyConfigSelector {

    public static final StaticProxyConfigSelector DIRECT = new StaticProxyConfigSelector(ProxyConfig.direct());

    /**
     * Constructs a {@link ProxyConfigSelector} which selects a static {@link ProxyConfig}
     * for all requests.
     */
    public static StaticProxyConfigSelector of(ProxyConfig proxyConfig) {
        return new StaticProxyConfigSelector(proxyConfig);
    }

    private final ProxyConfig proxyConfig;

    private StaticProxyConfigSelector(ProxyConfig proxyConfig) {
        this.proxyConfig = requireNonNull(proxyConfig, "proxyConfig");
    }

    @Override
    public ProxyConfig select(URI uri) {
        return proxyConfig;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
        // do nothing
    }
}
