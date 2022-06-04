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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The proxy protocol type.
 */
public enum ProxyType {

    /**
     * Proxy is absent.
     */
    DIRECT(false),

    /**
     * SOCKS4 proxy protocol.
     */
    SOCKS4(true),

    /**
     * SOCKS5 proxy protocol.
     */
    SOCKS5(true),

    /**
     * CONNECT proxy protocol.
     */
    CONNECT(true),

    /**
     * HAPROXY protocol.
     */
    HAPROXY(false);

    private final boolean isForwardProxy;

    ProxyType(boolean isForwardProxy) {
        this.isForwardProxy = isForwardProxy;
    }

    /**
     * Returns whether this proxy is a forward proxy type.
     */
    @UnstableApi
    public boolean isForwardProxy() {
        return isForwardProxy;
    }
}
