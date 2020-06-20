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

import java.net.ProxySelector;
import java.net.SocketAddress;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.UnstableApi;

/**
 * Selects the {@link ProxyConfig} to use when connecting to a network
 * resource specified by the {@code URI} parameter.
 * This class may be used to dynamically control what proxy configuration
 * to use for each request.
 */
@UnstableApi
public interface ProxyConfigSelector {

    /**
     * Selects the {@link ProxyConfig} to use when connecting to a network
     * resource specified by the {@link Endpoint} parameter.
     *
     * @param endpoint an endpoint containing the requested host and port
     * @return the selected proxy config which should be non-null
     */
    ProxyConfig select(Endpoint endpoint);

    /**
     * Called to indicate a connection attempt to the specified {@link Endpoint}
     * has failed. This callback may be utilized to decide which proxy configuration
     * should be used for each uri.
     *
     * @param endpoint an endpoint containing the requested host and port
     * @param sa the remote socket address of the proxy server
     * @param throwable the cause of the failure
     */
    void connectFailed(Endpoint endpoint, SocketAddress sa, Throwable throwable);

    /**
     * Provides a way to re-use an existing {@link ProxySelector} with some limitations.
     * See {@link WrappingProxyConfigSelector} for more details.
     */
    static ProxyConfigSelector wrap(ProxySelector proxySelector) {
        return WrappingProxyConfigSelector.of(requireNonNull(proxySelector, "proxySelector"));
    }
}
