/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.common.KeepAliveHandlerUtil.needsKeepAliveHandler;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.common.GracefulConnectionShutdownHandler;
import com.linecorp.armeria.internal.common.InitiateConnectionShutdown;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;

final class Http2ServerConnectionHandler extends AbstractHttp2ConnectionHandler {

    private final ServerConfig cfg;
    private final GracefulShutdownSupport gracefulShutdownSupport;
    private final Http2RequestDecoder requestDecoder;
    @Nullable
    private ServerHttp2ObjectEncoder responseEncoder;

    private final Http2GracefulConnectionShutdownHandler gracefulConnectionShutdownHandler;

    Http2ServerConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                 Http2Settings initialSettings, Channel channel, ServerConfig cfg,
                                 Timer keepAliveTimer, GracefulShutdownSupport gracefulShutdownSupport,
                                 AsciiString scheme) {

        super(decoder, encoder, initialSettings, newKeepAliveHandler(encoder, channel, cfg, keepAliveTimer));

        this.cfg = cfg;
        this.gracefulShutdownSupport = gracefulShutdownSupport;

        gracefulConnectionShutdownHandler = new Http2GracefulConnectionShutdownHandler(
                cfg.connectionDrainDurationMicros());

        requestDecoder = new Http2RequestDecoder(cfg, channel, scheme, keepAliveHandler());
        connection().addListener(requestDecoder);
        decoder().frameListener(requestDecoder);
    }

    private static KeepAliveHandler newKeepAliveHandler(
            Http2ConnectionEncoder encoder, Channel channel, ServerConfig cfg, Timer keepAliveTimer) {

        final long idleTimeoutMillis = cfg.idleTimeoutMillis();
        final boolean keepAliveOnPing = cfg.keepAliveOnPing();
        final long pingIntervalMillis = cfg.pingIntervalMillis();
        final long maxConnectionAgeMillis = cfg.maxConnectionAgeMillis();
        final int maxNumRequestsPerConnection = cfg.maxNumRequestsPerConnection();
        final boolean needsKeepAliveHandler = needsKeepAliveHandler(
                idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection);

        if (!needsKeepAliveHandler) {
            return new NoopKeepAliveHandler();
        }

        return new Http2ServerKeepAliveHandler(
                channel, encoder.frameWriter(), keepAliveTimer, idleTimeoutMillis,
                pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection, keepAliveOnPing);
    }

    ServerHttp2ObjectEncoder getOrCreateResponseEncoder(ChannelHandlerContext connectionHandlerCtx) {
        if (responseEncoder == null) {
            assert connectionHandlerCtx.handler() == this;
            responseEncoder = new ServerHttp2ObjectEncoder(connectionHandlerCtx, this);
            requestDecoder.initEncoder(responseEncoder);
        }
        return responseEncoder;
    }

    @Override
    protected boolean needsImmediateDisconnection() {
        return gracefulShutdownSupport.isShuttingDown() ||
               requestDecoder.goAwayHandler().receivedErrorGoAway() || keepAliveHandler().isClosing();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        cancelScheduledTasks();
        super.channelInactive(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        super.channelRegistered(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        super.handlerAdded(ctx);
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        cancelScheduledTasks();
        super.handlerRemoved0(ctx);
    }

    private void maybeInitializeKeepAliveHandler(ChannelHandlerContext ctx) {
        final KeepAliveHandler keepAliveHandler = keepAliveHandler();
        if (!(keepAliveHandler instanceof NoopKeepAliveHandler)) {
            final Channel channel = ctx.channel();
            if (channel.isActive() && channel.isRegistered()) {
                keepAliveHandler.initialize(ctx);
            }
        }
    }

    private void cancelScheduledTasks() {
        gracefulConnectionShutdownHandler.cancel();
        keepAliveHandler().destroy();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof InitiateConnectionShutdown) {
            setGoAwayDebugMessage("app-requested");
            gracefulConnectionShutdownHandler.handleInitiateConnectionShutdown(
                    ctx, (InitiateConnectionShutdown) evt);
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (keepAliveHandler().needsDisconnection()) {
            // Connection timed out or exceeded maximum number of requests.
            setGoAwayDebugMessage("max-age");
        }
        gracefulConnectionShutdownHandler.start(ctx, promise);
    }

    private final class Http2GracefulConnectionShutdownHandler extends GracefulConnectionShutdownHandler {
        Http2GracefulConnectionShutdownHandler(long drainDurationMicros) {
            super(drainDurationMicros);
        }

        /**
         * Send GOAWAY frame with stream ID 2^31-1 to signal clients that shutdown is imminent,
         * but still accept in flight streams.
         */
        @Override
        public void onDrainStart(ChannelHandlerContext ctx) {
            goAway(ctx, Integer.MAX_VALUE);
            ctx.flush();
        }

        /**
         * Start channel shutdown. Will send final GOAWAY with latest created stream ID.
         */
        @Override
        public void onDrainEnd(ChannelHandlerContext ctx) throws Exception {
            Http2ServerConnectionHandler.super.close(ctx, ctx.newPromise());
            // Cancel scheduled tasks after the call to the super class above to avoid triggering
            // needsImmediateDisconnection.
            cancelScheduledTasks();
        }
    }
}
