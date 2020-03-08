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

import javax.annotation.Nullable;

abstract class AbstractProxyConfigBuilder {

    private final InetSocketAddress proxyAddress;
    @Nullable
    private String username;
    @Nullable
    private String password;

    protected AbstractProxyConfigBuilder(InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    protected InetSocketAddress getProxyAddress() {
        return proxyAddress;
    }

    @Nullable
    protected String getUsername() {
        return username;
    }

    protected void setUsername(@Nullable String username) {
        this.username = username;
    }

    @Nullable
    protected String getPassword() {
        return password;
    }

    protected void setPassword(@Nullable String password) {
        this.password = password;
    }

    /**
     * Builds the {@link ProxyConfig} object.
     */
    public abstract ProxyConfig build();
}
