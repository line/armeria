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
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: add javadocs.
 */
public abstract class ProxyConfigSelector {
    private static final Logger logger = LoggerFactory.getLogger(ProxyConfigSelector.class);

    /**
     * TODO: add javadocs.
     */
    public abstract ProxyConfig select(URI uri);

    /**
     * TODO: add javadocs.
     */
    public abstract void connectFailed(URI uri, SocketAddress sa, Throwable throwable);

    /**
     * TODO: add javadocs.
     * FIXME: find a way to hide this.
     */
    public static final class WrappingProxyConfigSelector extends ProxyConfigSelector {

        /**
         * TODO: add javadocs.
         * FIXME: find a way to hide this.
         */
        public static WrappingProxyConfigSelector of(ProxySelector proxySelector) {
            return new WrappingProxyConfigSelector(proxySelector);
        }

        final ProxySelector proxySelector;

        private WrappingProxyConfigSelector(ProxySelector proxySelector) {
            this.proxySelector = proxySelector;
        }

        @Override
        public ProxyConfig select(URI uri) {
            final List<Proxy> proxies = proxySelector.select(uri);
            if (proxies == null || proxies.isEmpty()) {
                return ProxyConfig.direct();
            }

            final Proxy proxy = proxies.get(0);

            if (proxies.size() > 1) {
                logger.debug("Using the first proxy <{}> of <{}>.", proxy, proxies);
            }

            return ProxyConfig.fromProxy(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, Throwable throwable) {
            proxySelector.connectFailed(uri, sa, new IOException(throwable));
        }
    }
}
