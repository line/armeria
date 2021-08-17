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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * See {@link ProxyConfigSelector#of(ProxySelector)} for more information.
 */
final class WrappingProxyConfigSelector implements ProxyConfigSelector {
    private static final Logger logger = LoggerFactory.getLogger(WrappingProxyConfigSelector.class);

    /**
     * Converts {@link SessionProtocol}, {@link Endpoint} to a uri without serialization format.
     */
    private static URI toUri(SessionProtocol sessionProtocol, Endpoint endpoint) {
        try {
            return new URI(sessionProtocol.uriText(), endpoint.authority(), null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static InetSocketAddress resolve(InetSocketAddress inetSocketAddress) {
        if (!inetSocketAddress.isUnresolved()) {
            return inetSocketAddress;
        }
        return new InetSocketAddress(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
    }

    private static ProxyConfig toProxyConfig(@Nullable Proxy proxy) {
        if (proxy == null || proxy.address() == null) {
            return ProxyConfig.direct();
        }

        final InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
        switch (proxy.type()) {
            case HTTP:
                return ProxyConfig.connect(resolve(proxyAddress));
            case SOCKS:
                // NOTE: we may consider using {@code sun.net.SocksProxy} to determine the socksProxyVersion
                return ProxyConfig.socks5(resolve(proxyAddress));
            case DIRECT:
            default:
                return ProxyConfig.direct();
        }
    }

    static WrappingProxyConfigSelector of(ProxySelector proxySelector) {
        return new WrappingProxyConfigSelector(proxySelector);
    }

    private final ProxySelector proxySelector;

    private WrappingProxyConfigSelector(ProxySelector proxySelector) {
        this.proxySelector = proxySelector;
    }

    @Override
    public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpoint, "endpoint");
        final List<Proxy> proxies = proxySelector.select(toUri(protocol, endpoint));
        if (proxies == null || proxies.isEmpty()) {
            return ProxyConfig.direct();
        }

        final Proxy proxy = proxies.get(0);
        if (logger.isDebugEnabled() && proxies.size() > 1) {
            logger.debug("Using the first proxy <{}> of <{}>.", proxy, proxies);
        }
        return toProxyConfig(proxy);
    }

    @Override
    public void connectFailed(SessionProtocol protocol, Endpoint endpoint,
                              SocketAddress socketAddress, Throwable throwable) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpoint, "endpoint");
        proxySelector.connectFailed(endpoint.toUri(protocol),
                                    requireNonNull(socketAddress, "socketAddress"),
                                    new IOException(throwable));
    }
}
