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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: add javadocs.
 */
public final class WrappingProxyConfigSelector implements ProxyConfigSelector {
    private static final Logger logger = LoggerFactory.getLogger(WrappingProxyConfigSelector.class);

    private static InetSocketAddress resolve(InetSocketAddress inetSocketAddress) {
        if (!inetSocketAddress.isUnresolved()) {
            return inetSocketAddress;
        }
        return new InetSocketAddress(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
    }

    private static ProxyConfig toProxyConfig(Proxy proxy) {
        if (proxy.address() == null) {
            return ProxyConfig.direct();
        }
        if (!(proxy.address() instanceof InetSocketAddress)) {
            logger.warn("Invalid proxy address for <{}>.", proxy);
            return ProxyConfig.direct();
        }

        final InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
        switch (proxy.type()) {
            case HTTP:
                return ProxyConfig.connect(resolve(proxyAddress));
            case SOCKS:
                // TODO: find a way to use flag "socksProxyVersion"
                return ProxyConfig.socks5(resolve(proxyAddress));
            case DIRECT:
            default:
                return ProxyConfig.direct();
        }
    }

    /**
     * TODO: add javadocs.
     */
    public static WrappingProxyConfigSelector of(@Nullable ProxySelector proxySelector) {
        return new WrappingProxyConfigSelector(proxySelector);
    }

    @Nullable
    final ProxySelector proxySelector;

    private WrappingProxyConfigSelector(@Nullable ProxySelector proxySelector) {
        this.proxySelector = proxySelector;
    }

    @Override
    public ProxyConfig select(URI uri) {
        if (proxySelector == null) {
            return ProxyConfig.direct();
        }
        final List<Proxy> proxies = proxySelector.select(uri);
        if (proxies == null || proxies.isEmpty()) {
            return ProxyConfig.direct();
        }

        final Proxy proxy = proxies.get(0);
        if (proxies.size() > 1) {
            logger.debug("Using the first proxy <{}> of <{}>.", proxy, proxies);
        }
        return toProxyConfig(proxy);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
        if (proxySelector == null) {
            return;
        }
        proxySelector.connectFailed(uri, sa, new IOException(throwable));
    }
}
