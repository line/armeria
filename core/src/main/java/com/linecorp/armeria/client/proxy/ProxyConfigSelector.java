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

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Selects the {@link ProxyConfig} to use when connecting to a network
 * resource specified by the {@link SessionProtocol} and {@link Endpoint} parameter.
 * This class may be used to dynamically control what proxy configuration
 * to use for each request.
 *
 * <p>It should be noted that the only guarantee provided is for a single request,
 * the {@link Endpoint} called with {@link #select(SessionProtocol, Endpoint)} will be equal to
 * the {@link Endpoint} called with {@link #connectFailed(SessionProtocol, Endpoint,
 * SocketAddress, Throwable)}.
 *
 * <p>For instance, the actual {@link SessionProtocol} of the connection may differ from
 * the originally requested {@link SessionProtocol} depending on the result of protocol negotiation.
 * Similarly, the actual {@link Endpoint} of the request may differ from the originally requested
 * {@link Endpoint}.
 */
@UnstableApi
@FunctionalInterface
public interface ProxyConfigSelector {

    /**
     * Provides a way to reuse an existing {@link ProxySelector} with some limitations.
     * <ul>
     *   <li>Incompatibilities when used with JDK's default {@link ProxySelector} implementation:
     *     <ul>
     *       <li>Some properties like socksProxyVersion aren't respected</li>
     *       <li>This class doesn't attempt to resolve scheme format differences.
     *       However, armeria uses some schemes such as "h1c", "h2" which aren't supported by JDK's
     *       default {@link ProxySelector}. This may be a source of unexpected behavior.</li>
     *     </ul>
     *   </li>
     *   <li>Selecting multiple {@link Proxy} isn't supported</li>
     * </ul>
     */
    static ProxyConfigSelector of(ProxySelector proxySelector) {
        return WrappingProxyConfigSelector.of(requireNonNull(proxySelector, "proxySelector"));
    }

    /**
     * Returns a {@link ProxyConfigSelector} which selects a static {@link ProxyConfig} for all requests.
     */
    static ProxyConfigSelector of(ProxyConfig proxyConfig) {
        return StaticProxyConfigSelector.of(requireNonNull(proxyConfig, "proxyConfig"));
    }

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
    default void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                               SocketAddress sa, Throwable throwable) {}
}
