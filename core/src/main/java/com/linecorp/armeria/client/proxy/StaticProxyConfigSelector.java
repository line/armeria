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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;

final class StaticProxyConfigSelector implements ProxyConfigSelector {

    private static final StaticProxyConfigSelector DIRECT = new StaticProxyConfigSelector(ProxyConfig.direct());

    static StaticProxyConfigSelector of(ProxyConfig proxyConfig) {
        if (proxyConfig == ProxyConfig.direct()) {
            return DIRECT;
        }
        return new StaticProxyConfigSelector(proxyConfig);
    }

    private final ProxyConfig proxyConfig;

    private StaticProxyConfigSelector(ProxyConfig proxyConfig) {
        this.proxyConfig = requireNonNull(proxyConfig, "proxyConfig");
    }

    @Override
    public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
        return proxyConfig;
    }
}
