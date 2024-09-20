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

import java.lang.reflect.Array;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.SslContextFactory;
import com.linecorp.armeria.internal.common.SslContextFactory.SslContextMode;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.ssl.SslContext;

final class Bootstraps {

    private final EventLoop eventLoop;
    private final SslContext sslCtxHttp1Only;
    private final SslContext sslCtxHttp1Or2;
    @Nullable
    private final SslContextFactory sslContextFactory;

    private final HttpClientFactory clientFactory;
    private final Bootstrap inetBaseBootstrap;
    @Nullable
    private final Bootstrap unixBaseBootstrap;
    private final Bootstrap[][] inetBootstraps;
    private final Bootstrap @Nullable [][] unixBootstraps;

    Bootstraps(HttpClientFactory clientFactory, EventLoop eventLoop,
               SslContext sslCtxHttp1Or2, SslContext sslCtxHttp1Only,
               @Nullable SslContextFactory sslContextFactory) {
        this.eventLoop = eventLoop;
        this.sslCtxHttp1Or2 = sslCtxHttp1Or2;
        this.sslCtxHttp1Only = sslCtxHttp1Only;
        this.sslContextFactory = sslContextFactory;
        this.clientFactory = clientFactory;

        inetBaseBootstrap = clientFactory.newInetBootstrap();
        inetBaseBootstrap.group(eventLoop);
        inetBootstraps = staticBootstrapMap(inetBaseBootstrap);

        unixBaseBootstrap = clientFactory.newUnixBootstrap();
        if (unixBaseBootstrap != null) {
            unixBaseBootstrap.group(eventLoop);
            unixBootstraps = staticBootstrapMap(unixBaseBootstrap);
        } else {
            unixBootstraps = null;
        }
    }

    private Bootstrap[][] staticBootstrapMap(Bootstrap baseBootstrap) {
        final Set<SessionProtocol> sessionProtocols = httpAndHttpsValues();
        final Bootstrap[][] maps = (Bootstrap[][]) Array.newInstance(
                Bootstrap.class, SessionProtocol.values().length, 2);
        // Attempting to access the array with an unallowed protocol will trigger NPE,
        // which will help us find a bug.
        for (SessionProtocol p : sessionProtocols) {
            final SslContext sslCtx = determineSslContext(p);
            createAndSetBootstrap(baseBootstrap, maps, p, sslCtx, true);
            createAndSetBootstrap(baseBootstrap, maps, p, sslCtx, false);
        }
        return maps;
    }

    /**
     * Determine {@link SslContext} by the specified {@link SessionProtocol}.
     */
    SslContext determineSslContext(SessionProtocol desiredProtocol) {
        return desiredProtocol.isExplicitHttp1() ? sslCtxHttp1Only : sslCtxHttp1Or2;
    }

    private Bootstrap select(boolean isDomainSocket, SessionProtocol desiredProtocol,
                             SerializationFormat serializationFormat) {
        final Bootstrap[][] bootstraps = isDomainSocket ? unixBootstraps : inetBootstraps;
        assert bootstraps != null;
        return bootstraps[desiredProtocol.ordinal()][toIndex(serializationFormat)];
    }

    private void createAndSetBootstrap(Bootstrap baseBootstrap, Bootstrap[][] maps,
                                       SessionProtocol desiredProtocol, SslContext sslContext,
                                       boolean webSocket) {
        maps[desiredProtocol.ordinal()][toIndex(webSocket)] = newBootstrap(baseBootstrap, desiredProtocol,
                                                                           sslContext, webSocket, false);
    }

    private static int toIndex(boolean webSocket) {
        return webSocket ? 1 : 0;
    }

    private static int toIndex(SerializationFormat serializationFormat) {
        return toIndex(serializationFormat == SerializationFormat.WS);
    }

    /**
     * Returns a {@link Bootstrap} corresponding to the specified {@link SocketAddress}
     * {@link SessionProtocol} and {@link SerializationFormat}.
     */
    Bootstrap getOrCreate(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                          SerializationFormat serializationFormat) {
        if (!httpAndHttpsValues().contains(desiredProtocol)) {
            throw new IllegalArgumentException("Unsupported session protocol: " + desiredProtocol);
        }

        final boolean isDomainSocket = remoteAddress instanceof DomainSocketAddress;
        if (isDomainSocket && unixBaseBootstrap == null) {
            throw new IllegalArgumentException("Domain sockets are not supported by " +
                                               eventLoop.getClass().getName());
        }

        if (sslContextFactory == null || !desiredProtocol.isTls()) {
            return select(isDomainSocket, desiredProtocol, serializationFormat);
        }

        final Bootstrap baseBootstrap = isDomainSocket ? unixBaseBootstrap : inetBaseBootstrap;
        assert baseBootstrap != null;
        return newBootstrap(baseBootstrap, remoteAddress, desiredProtocol, serializationFormat);
    }

    private Bootstrap newBootstrap(Bootstrap baseBootstrap, SocketAddress remoteAddress,
                                   SessionProtocol desiredProtocol,
                                   SerializationFormat serializationFormat) {
        final boolean webSocket = serializationFormat == SerializationFormat.WS;
        final SslContext sslContext = newSslContext(remoteAddress, desiredProtocol);
        return newBootstrap(baseBootstrap, desiredProtocol, sslContext, webSocket, true);
    }

    private Bootstrap newBootstrap(Bootstrap baseBootstrap, SessionProtocol desiredProtocol,
                                   SslContext sslContext, boolean webSocket, boolean closeSslContext) {
        final Bootstrap bootstrap = baseBootstrap.clone();
        bootstrap.handler(clientChannelInitializer(desiredProtocol, sslContext, webSocket, closeSslContext));
        return bootstrap;
    }

    SslContext getOrCreateSslContext(SocketAddress remoteAddress, SessionProtocol desiredProtocol) {
        if (sslContextFactory == null) {
            return determineSslContext(desiredProtocol);
        } else {
            return newSslContext(remoteAddress, desiredProtocol);
        }
    }

    private SslContext newSslContext(SocketAddress remoteAddress, SessionProtocol desiredProtocol) {
        final String hostname;
        if (remoteAddress instanceof InetSocketAddress) {
            hostname = ((InetSocketAddress) remoteAddress).getHostString();
        } else {
            assert remoteAddress instanceof DomainSocketAddress;
            hostname = "unix:" + ((DomainSocketAddress) remoteAddress).path();
        }

        final SslContextMode sslContextMode =
                desiredProtocol.isExplicitHttp1() ? SslContextFactory.SslContextMode.CLIENT_HTTP1_ONLY
                                                  : SslContextFactory.SslContextMode.CLIENT;
        assert sslContextFactory != null;
        return sslContextFactory.getOrCreate(sslContextMode, hostname);
    }

    boolean shouldReleaseSslContext(SslContext sslContext) {
        return sslContext != sslCtxHttp1Only && sslContext != sslCtxHttp1Or2;
    }

    void releaseSslContext(SslContext sslContext) {
        if (sslContextFactory != null) {
            sslContextFactory.release(sslContext);
        }
    }

    private ChannelInitializer<Channel> clientChannelInitializer(SessionProtocol p, SslContext sslCtx,
                                                                 boolean webSocket, boolean closeSslContext) {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                if (closeSslContext) {
                    ch.closeFuture().addListener(unused -> releaseSslContext(sslCtx));
                }
                ch.pipeline().addLast(new HttpClientPipelineConfigurator(
                        clientFactory, webSocket, p, sslCtx));
            }
        };
    }
}
