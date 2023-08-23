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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.SessionProtocol.httpAndHttpsValues;

import java.lang.reflect.Array;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.ssl.SslContext;

final class Bootstraps {
    private final Bootstrap[][] inetBootstraps;
    @Nullable
    private final Bootstrap[][] unixBootstraps;

    private final int connectTimeoutMillis;

    private final EventLoop eventLoop;

    private final SslContext sslCtxHttp1Only;

    private final SslContext sslCtxHttp1Or2;

    Bootstraps(HttpClientFactory clientFactory, EventLoop eventLoop, SslContext sslCtxHttp1Only,
               SslContext sslCtxHttp1Or2) {
        this.eventLoop = eventLoop;
        this.sslCtxHttp1Only = sslCtxHttp1Only;
        this.sslCtxHttp1Or2 = sslCtxHttp1Or2;
        final Bootstrap inetBaseBootstrap = clientFactory.newInetBootstrap();
        final Bootstrap unixBaseBootstrap = clientFactory.newUnixBootstrap();
        inetBootstraps = newBootstrapMap(inetBaseBootstrap, clientFactory, eventLoop);
        if (unixBaseBootstrap != null) {
            unixBootstraps = newBootstrapMap(unixBaseBootstrap, clientFactory, eventLoop);
        } else {
            unixBootstraps = null;
        }
        connectTimeoutMillis = (Integer) inetBaseBootstrap.config().options()
                                                          .get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
    }

    Bootstrap get(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                  SerializationFormat serializationFormat) {
        if (!httpAndHttpsValues().contains(desiredProtocol)) {
            throw new NullPointerException("Unsupported session protocol: " + desiredProtocol);
        }

        if (remoteAddress instanceof InetSocketAddress) {
            return select(inetBootstraps, desiredProtocol, serializationFormat);
        }

        assert remoteAddress instanceof DomainSocketAddress : remoteAddress;

        if (unixBootstraps == null) {
            throw new IllegalArgumentException("Domain sockets are not supported by " +
                                               eventLoop.getClass().getName());
        }

        return select(unixBootstraps, desiredProtocol, serializationFormat);
    }

    int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    private Bootstrap select(Bootstrap[][] bootstraps, SessionProtocol desiredProtocol, SerializationFormat serializationFormat) {
        return bootstraps[desiredProtocol.ordinal()][toIndex(serializationFormat)];
    }

    private Bootstrap[][] newBootstrapMap(Bootstrap baseBootstrap,
                                          HttpClientFactory clientFactory,
                                          EventLoop eventLoop) {
        baseBootstrap.group(eventLoop);
        final Set<SessionProtocol> sessionProtocols = httpAndHttpsValues();
        final Bootstrap[][] maps = (Bootstrap[][]) Array.newInstance(
                Bootstrap.class, SessionProtocol.values().length, 2);
        // Attempting to access the array with an unallowed protocol will trigger NPE,
        // which will help us find a bug.
        for (SessionProtocol p : sessionProtocols) {
            final SslContext sslCtx = determineSslContext(p);
            setBootstrap(baseBootstrap.clone(), clientFactory, maps, p, sslCtx, true);
            setBootstrap(baseBootstrap.clone(), clientFactory, maps, p, sslCtx, false);
        }
        return maps;
    }
    private void setBootstrap(Bootstrap bootstrap, HttpClientFactory clientFactory, Bootstrap[][] maps,
                              SessionProtocol p, SslContext sslCtx, boolean webSocket) {
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(
                        new HttpClientPipelineConfigurator(clientFactory, webSocket, p, sslCtx));
            }
        });
        maps[p.ordinal()][toIndex(webSocket)] = bootstrap;
    }

    private int toIndex(boolean webSocket) {
        return webSocket ? 1 : 0;
    }

    private int toIndex(SerializationFormat serializationFormat) {
        return toIndex(serializationFormat == SerializationFormat.WS);
    }

    private SslContext determineSslContext(SessionProtocol desiredProtocol) {
        return desiredProtocol == SessionProtocol.H1 || desiredProtocol == SessionProtocol.H1C ?
               sslCtxHttp1Only : sslCtxHttp1Or2;
    }
}