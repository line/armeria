/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.http;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.client.SessionProtocolNegotiationCache;
import com.linecorp.armeria.client.SessionProtocolNegotiationException;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

class HttpSessionChannelFactory implements Function<PoolKey, Future<Channel>> {

    private final Bootstrap baseBootstrap;
    private final EventLoop eventLoop;
    private final Map<SessionProtocol, Bootstrap> bootstrapMap;
    private final SessionOptions options;

    HttpSessionChannelFactory(Bootstrap bootstrap,SessionOptions options) {
        baseBootstrap = requireNonNull(bootstrap);
        eventLoop = (EventLoop) bootstrap.config().group();

        bootstrapMap = Collections.synchronizedMap(new EnumMap<>(SessionProtocol.class));
        this.options = options;
    }

    @Override
    public Future<Channel> apply(PoolKey key) {
        final InetSocketAddress remoteAddress = key.remoteAddress();
        final SessionProtocol protocol = key.sessionProtocol();

        if (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, protocol)) {
            // Fail immediately if it is sure that the remote address does not support the requested protocol.
            return eventLoop.newFailedFuture(
                    new SessionProtocolNegotiationException(protocol, "previously failed negotiation"));
        }

        final Promise<Channel> sessionPromise = eventLoop.newPromise();
        connect(remoteAddress, protocol, sessionPromise);

        return sessionPromise;
    }

    void connect(SocketAddress remoteAddress, SessionProtocol protocol, Promise<Channel> sessionPromise) {
        final Bootstrap bootstrap = bootstrap(protocol);
        final ChannelFuture connectFuture = bootstrap.connect(remoteAddress);

        connectFuture.addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                initSession(protocol, future, sessionPromise);
            } else {
                sessionPromise.setFailure(future.cause());
            }
        });
    }

    private Bootstrap bootstrap(SessionProtocol sessionProtocol) {
        return bootstrapMap.computeIfAbsent(sessionProtocol, sp -> {
            Bootstrap bs = baseBootstrap.clone();
            bs.handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ch.pipeline().addLast(new HttpClientPipelineConfigurator(sp, options));
                }
            });
            return bs;
        });
    }

    private void initSession(SessionProtocol protocol, ChannelFuture connectFuture,
                             Promise<Channel> sessionPromise) {
        assert connectFuture.isSuccess();

        final Channel ch = connectFuture.channel();
        final EventLoop eventLoop = ch.eventLoop();
        assert eventLoop.inEventLoop();

        final ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            if (sessionPromise.tryFailure(new SessionProtocolNegotiationException(
                    protocol, "connection established, but session creation timed out: " + ch))) {
                ch.close();
            }
        }, options.connectTimeoutMillis(), TimeUnit.MILLISECONDS);

        ch.pipeline().addLast(new HttpSessionHandler(this, ch, sessionPromise, timeoutFuture));
    }
}
