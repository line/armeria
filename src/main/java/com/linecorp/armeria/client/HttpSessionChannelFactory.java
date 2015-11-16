/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TimeoutException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.OneTimeTask;

class HttpSessionChannelFactory implements Function<PoolKey, Future<Channel>> {

    static final AttributeKey<Void> SESSION_ACTIVE =
            AttributeKey.valueOf(HttpSessionChannelFactory.class, "SESSION_ACTIVE");
    static final ChannelHealthChecker HEALTH_CHECKER = channel -> {
        EventLoop eventLoop = channel.eventLoop();
        return channel.isActive() && channel.hasAttr(SESSION_ACTIVE) ?
               eventLoop.newSucceededFuture(Boolean.TRUE) : eventLoop.newSucceededFuture(Boolean.FALSE);
    };

    private final Bootstrap baseBootstrap;
    private final Map<SessionProtocol, Bootstrap> bootstrapMap;
    private final RemoteInvokerOptions options;

    HttpSessionChannelFactory(Bootstrap bootstrap, RemoteInvokerOptions options) {
        baseBootstrap = requireNonNull(bootstrap);
        bootstrapMap = Collections.synchronizedMap(new EnumMap<>(SessionProtocol.class));
        this.options = options;
    }

    @Override
    public Future<Channel> apply(PoolKey key) {
        final InetSocketAddress remoteAddress = key.remoteAddress();
        final SessionProtocol sessionProtocol = key.sessionProtocol();

        final Bootstrap bootstrap = bootstrap(sessionProtocol);
        ChannelFuture channelFuture = bootstrap.connect(remoteAddress);
        final Channel ch = channelFuture.channel();
        final Promise<Channel> channelPromise = channelFuture.channel().eventLoop().newPromise();

        if (channelFuture.isDone()) {
            notifyConnnect(channelFuture, ch, channelPromise);
        } else {
            channelFuture.addListener((Future<Void> future) -> notifyConnnect(future, ch, channelPromise));
        }

        return channelPromise;
    }

    private void notifyConnnect(Future<Void> fut, Channel ch, Promise<Channel> channelPromise) {
        assert fut.isDone();
        if (fut.isSuccess()) {
            watchSessionActive(ch, channelPromise);
        } else {
            channelPromise.setFailure(fut.cause());
        }
    }

    private Bootstrap bootstrap(SessionProtocol sessionProtocol) {
        return bootstrapMap.computeIfAbsent(sessionProtocol, sp -> {
            Bootstrap bs = baseBootstrap.clone();
            bs.handler(new HttpConfigurator(sp, options, new DefaultSessionListener()));
            return bs;
        });
    }

    private Future<Channel> watchSessionActive(Channel ch, Promise<Channel> promise) {
        EventLoop eventLoop = ch.eventLoop();

        if (eventLoop.inEventLoop()) {
            watchSessionActive0(ch, promise);
        } else {
            eventLoop.execute(new OneTimeTask() {
                @Override
                public void run() {
                    watchSessionActive0(ch, promise);
                }
            });
        }
        return promise;
    }

    private void watchSessionActive0(final Channel ch, Promise<Channel> result) {
        assert ch.eventLoop().inEventLoop();

        if (ch.hasAttr(SESSION_ACTIVE)) {
            result.setSuccess(ch);
        }

        ScheduledFuture<?> timeoutFuture = ch.eventLoop().schedule(new OneTimeTask() {
            @Override
            public void run() {
                result.setFailure(
                        new TimeoutException("connection established, but session creation timed out: " + ch));
            }
        }, options.connectTimeoutMillis(), TimeUnit.MILLISECONDS);

        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof SessionProtocol) {
                    timeoutFuture.cancel(false);
                    result.setSuccess(ctx.channel());
                    ctx.pipeline().remove(this);
                }
                ctx.fireUserEventTriggered(evt);
            }
        });
    }

    private static class DefaultSessionListener implements SessionListener {
        private HttpSessionHandler sessionHandler;

        @Override
        public void sessionActivated(ChannelHandlerContext ctx, SessionProtocol sessionProtocol) {
            ctx.pipeline().addLast(sessionHandler = new HttpSessionHandler(sessionProtocol));
            ctx.channel().attr(SESSION_ACTIVE).set(null);
            ctx.fireUserEventTriggered(sessionProtocol);
        }

        @Override
        public void sessionDeactivated(ChannelHandlerContext ctx) {
            ctx.channel().attr(SESSION_ACTIVE).remove();
            if (sessionHandler != null) {
                sessionHandler.deactivateSession();
            }
        }
    }
}
