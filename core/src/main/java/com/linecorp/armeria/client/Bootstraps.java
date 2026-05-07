/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.common.SessionProtocol.httpAndHttpsValues;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.ssl.SslContext;

final class Bootstraps {

    private final EventLoop eventLoop;
    private final SslContextFactory sslContextFactory;

    private final HttpClientFactory clientFactory;
    private final Bootstrap inetBaseBootstrap;
    private final BootstrapSslContexts bootstrapSslContexts;
    @Nullable
    private final Bootstrap unixBaseBootstrap;
    private final Map<BootstrapKey, Bootstrap> bootstraps;

    Bootstraps(HttpClientFactory clientFactory, EventLoop eventLoop,
               SslContextFactory sslContextFactory, BootstrapSslContexts bootstrapSslContexts) {
        this.eventLoop = eventLoop;
        this.sslContextFactory = sslContextFactory;
        this.clientFactory = clientFactory;

        inetBaseBootstrap = clientFactory.newInetBootstrap();
        this.bootstrapSslContexts = bootstrapSslContexts;
        inetBaseBootstrap.group(eventLoop);

        unixBaseBootstrap = clientFactory.newUnixBootstrap();
        if (unixBaseBootstrap != null) {
            unixBaseBootstrap.group(eventLoop);
        }

        bootstraps = staticBootstrapMap();
    }

    private Map<BootstrapKey, Bootstrap> staticBootstrapMap() {
        final Map<BootstrapKey, Bootstrap> map = new HashMap<>();
        populateBootstraps(map, inetBaseBootstrap, false);
        if (unixBaseBootstrap != null) {
            populateBootstraps(map, unixBaseBootstrap, true);
        }
        return Collections.unmodifiableMap(map);
    }

    private void populateBootstraps(Map<BootstrapKey, Bootstrap> map,
                                    Bootstrap baseBootstrap, boolean domainSocket) {
        for (HttpPreference p : HttpPreference.values()) {
            final SslContext sslCtx = bootstrapSslContexts.getSslContext(p);
            createAndSetBootstrap(baseBootstrap, map, domainSocket, p, null, true);
            createAndSetBootstrap(baseBootstrap, map, domainSocket, p, null, false);
            createAndSetBootstrap(baseBootstrap, map, domainSocket, p, sslCtx, true);
            createAndSetBootstrap(baseBootstrap, map, domainSocket, p, sslCtx, false);
        }
    }

    private Bootstrap select(boolean isDomainSocket, HttpPreference httpPreference,
                             SessionProtocol sessionProtocol, SerializationFormat serializationFormat) {
        final BootstrapKey key = BootstrapKey.of(isDomainSocket, httpPreference, sessionProtocol.isTls(),
                                                 serializationFormat == SerializationFormat.WS);
        final Bootstrap bootstrap = bootstraps.get(key);
        assert bootstrap != null : "No bootstrap found for " + key;
        return bootstrap;
    }

    private void createAndSetBootstrap(Bootstrap baseBootstrap, Map<BootstrapKey, Bootstrap> map,
                                       boolean domainSocket, HttpPreference httpPreference,
                                       @Nullable SslContext sslContext, boolean webSocket) {
        final BootstrapKey key = BootstrapKey.of(domainSocket, httpPreference, sslContext != null, webSocket);
        map.put(key, newBootstrap(baseBootstrap, httpPreference, sslContext, webSocket, false));
    }

    /**
     * Returns a {@link Bootstrap} corresponding to the specified {@link SocketAddress}
     * {@link SessionProtocol} and {@link SerializationFormat}.
     */
    Bootstrap getOrCreate(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                          HttpPreference httpPreference,
                          SerializationFormat serializationFormat, ClientTlsSpec tlsSpec) {
        if (!httpAndHttpsValues().contains(desiredProtocol)) {
            throw new IllegalArgumentException("Unsupported session protocol: " + desiredProtocol);
        }

        final boolean isDomainSocket = remoteAddress instanceof DomainSocketAddress;
        if (isDomainSocket && unixBaseBootstrap == null) {
            throw new IllegalArgumentException("Domain sockets are not supported by " +
                                               eventLoop.getClass().getName());
        }

        if (!desiredProtocol.isTls()) {
            return select(isDomainSocket, httpPreference, desiredProtocol, serializationFormat);
        }
        final ClientTlsSpec defaultTlsSpec = bootstrapSslContexts.getClientTlsSpec(desiredProtocol);
        if (Objects.equals(defaultTlsSpec, tlsSpec)) {
            return select(isDomainSocket, httpPreference, desiredProtocol, serializationFormat);
        }

        final Bootstrap baseBootstrap = isDomainSocket ? unixBaseBootstrap : inetBaseBootstrap;
        assert baseBootstrap != null;
        return newBootstrap(baseBootstrap, httpPreference, serializationFormat, tlsSpec);
    }

    private Bootstrap newBootstrap(Bootstrap baseBootstrap,
                                   HttpPreference httpPreference,
                                   SerializationFormat serializationFormat, ClientTlsSpec tlsSpec) {
        final boolean webSocket = serializationFormat == SerializationFormat.WS;
        final SslContext sslContext = sslContextFactory.getOrCreate(tlsSpec);
        return newBootstrap(baseBootstrap, httpPreference, sslContext, webSocket, true);
    }

    private Bootstrap newBootstrap(Bootstrap baseBootstrap, HttpPreference httpPreference,
                                   @Nullable SslContext sslContext, boolean webSocket,
                                   boolean closeSslContext) {
        final Bootstrap bootstrap = baseBootstrap.clone();
        bootstrap.handler(clientChannelInitializer(httpPreference, sslContext, webSocket, closeSslContext));
        return bootstrap;
    }

    SslContext getOrCreateSslContext(ClientTlsSpec tlsSpec) {
        return sslContextFactory.getOrCreate(tlsSpec);
    }

    void release(SslContext sslContext) {
        sslContextFactory.release(sslContext);
    }

    private ChannelInitializer<Channel> clientChannelInitializer(
            HttpPreference httpPreference, @Nullable SslContext sslCtx,
            boolean webSocket, boolean closeSslContext) {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                if (closeSslContext && sslCtx != null) {
                    ch.closeFuture().addListener(unused -> release(sslCtx));
                }
                ch.pipeline().addLast(new HttpClientPipelineConfigurator(
                        clientFactory, webSocket, httpPreference, sslCtx));
            }
        };
    }

    private static final class BootstrapKey {

        // 2 (domainSocket) * 4 (httpPreference) * 2 (tls) * 2 (webSocket) = 32
        private static final BootstrapKey[] CACHE;

        static {
            final HttpPreference[] preferences = HttpPreference.values();
            assert preferences.length <= 4 : "BootstrapKey bit layout assumes at most 4 HttpPreference values";
            CACHE = new BootstrapKey[preferences.length * 8]; // 8 = 2 (domainSocket) * 2 (tls) * 2 (webSocket)
            for (HttpPreference p : preferences) {
                for (int ds = 0; ds <= 1; ds++) {
                    for (int tls = 0; tls <= 1; tls++) {
                        for (int ws = 0; ws <= 1; ws++) {
                            final boolean dsBool = ds == 1;
                            final boolean tlsBool = tls == 1;
                            final boolean wsBool = ws == 1;
                            CACHE[index(dsBool, p, tlsBool, wsBool)] =
                                    new BootstrapKey(dsBool, p, tlsBool, wsBool);
                        }
                    }
                }
            }
        }

        static BootstrapKey of(boolean domainSocket, HttpPreference httpPreference,
                               boolean tls, boolean webSocket) {
            return CACHE[index(domainSocket, httpPreference, tls, webSocket)];
        }

        private static int index(boolean domainSocket, HttpPreference httpPreference,
                                  boolean tls, boolean webSocket) {
            //  [domainSocket: 1 bit][httpPreference: 2 bits][tls: 1 bit][webSocket: 1 bit]
            return ((domainSocket ? 1 : 0) << 4) |
                   (httpPreference.ordinal() << 2) |
                   ((tls ? 1 : 0) << 1) |
                   (webSocket ? 1 : 0);
        }

        private final boolean domainSocket;
        private final HttpPreference httpPreference;
        private final boolean tls;
        private final boolean webSocket;
        private final int hashCode;

        private BootstrapKey(boolean domainSocket, HttpPreference httpPreference,
                             boolean tls, boolean webSocket) {
            this.domainSocket = domainSocket;
            this.httpPreference = httpPreference;
            this.tls = tls;
            this.webSocket = webSocket;
            hashCode = Objects.hash(domainSocket, httpPreference, tls, webSocket);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BootstrapKey)) {
                return false;
            }
            final BootstrapKey that = (BootstrapKey) o;
            return domainSocket == that.domainSocket &&
                   httpPreference == that.httpPreference &&
                   tls == that.tls &&
                   webSocket == that.webSocket;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return "BootstrapKey(domainSocket=" + domainSocket + ", " + httpPreference +
                   ", tls=" + tls + ", webSocket=" + webSocket + ')';
        }
    }
}
