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

import com.google.common.base.MoreObjects;

/**
 * Contains CONNECT proxy configuration.
 */
public class ConnectProxyConfig extends ProxyConfig {

    @Nullable
    private final String userName;

    @Nullable
    private final String password;

    private final boolean useSsl;

    ConnectProxyConfig(InetSocketAddress proxyAddress, @Nullable String userName,
                       @Nullable String password, boolean useSsl) {
        super(proxyAddress);
        this.userName = userName;
        this.password = password;
        this.useSsl = useSsl;
    }

    /**
     * The configured userName.
     */
    @Nullable
    public String userName() {
        return userName;
    }

    /**
     * The configured password.
     */
    @Nullable
    public String password() {
        return password;
    }

    /**
     * Whether ssl is enabled.
     */
    public boolean useSsl() {
        return useSsl;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("proxyAddress", proxyAddress())
                          .add("userName", userName)
                          .add("password", password)
                          .add("useSsl", useSsl)
                          .toString();
    }
}
