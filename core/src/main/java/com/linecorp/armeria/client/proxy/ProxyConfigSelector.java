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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.UnstableApi;

/**
 * Selects the {@link ProxyConfig} to use when connecting to a network
 * resource specified by the {@link SessionProtocol} and {@link Endpoint} parameter.
 * This class may be used to dynamically control what proxy configuration
 * to use for each request.

 * <p>It should be noted that the only guarantee provided is the {@link Endpoint} called with
 * {@code select} will be the same as the {@link Endpoint} called with {@code connectFailed}.</p>
 *
 * <p>For instance, the invoked {@link SessionProtocol} may change depending on HTTP/2 upgrade.
 * Additionally, we should note the {@link Endpoint} used to construct {@link Client} will not
 * necessarily be equal to the {@link Endpoint} in either callback method.</p>
 */
@UnstableApi
public interface ProxyConfigSelector {

    /**
     * Selects the {@link ProxyConfig} to use when connecting to a network
     * resource specified by the {@link SessionProtocol} and {@link Endpoint} parameter.
     *
     * @param protocol the protocol associated with the endpoint
     * @param endpoint an endpoint containing the requested host and port
     * @return the selected proxy config which should be non-null
     */
    ProxyConfig select(SessionProtocol protocol, Endpoint endpoint);

    /**
     * Called to indicate a connection attempt to the specified {@link SessionProtocol}
     * and {@link Endpoint} has failed.
     *
     * @param protocol the protocol associated with the endpoint
     * @param endpoint an endpoint containing the requested host and port
     * @param sa the remote socket address of the proxy server
     * @param throwable the cause of the failure
     */
    void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                       SocketAddress sa, Throwable throwable);

    /**
     * Provides a way to re-use an existing {@link ProxySelector} with some limitations.
     * See {@link WrappingProxyConfigSelector} for more details.
     */
    static ProxyConfigSelector wrap(ProxySelector proxySelector) {
        return WrappingProxyConfigSelector.of(requireNonNull(proxySelector, "proxySelector"));
    }
}
