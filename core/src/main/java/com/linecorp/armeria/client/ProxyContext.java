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

package com.linecorp.armeria.client;

import java.net.URI;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;

final class ProxyContext {
    private final ProxyConfig proxyConfig;
    private final URI reqUri;

    /**
     * Constructor for a value object containing client-side proxy fields.
     * These fields are used to determine which connection will be used,
     * or how a new connection will be opened.
     *
     * @param reqUri the original request URI for a proxy. This value is mainly used to invoke callbacks
     *               in {@link ProxyConfigSelector} when a new connection is opened.
     */
    ProxyContext(ProxyConfig proxyConfig, URI reqUri) {
        this.proxyConfig = proxyConfig;
        this.reqUri = reqUri;
    }

    ProxyConfig proxyConfig() {
        return proxyConfig;
    }

    URI reqUri() {
        return reqUri;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyConfig", proxyConfig)
                          .add("reqUri", reqUri)
                          .toString();
    }
}
