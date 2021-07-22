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

import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;

final class Http2ServerConnectionHandler extends AbstractHttp2ConnectionHandler {

    private final GracefulShutdownSupport gracefulShutdownSupport;
    private final Http2RequestDecoder requestDecoder;

    private final KeepAliveHandler keepAliveHandler;

    Http2ServerConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                 Http2Settings initialSettings, Channel channel, ServerConfig config,
                                 Timer keepAliveTimer, GracefulShutdownSupport gracefulShutdownSupport,
                                 String scheme) {

        super(decoder, encoder, initialSettings);
        this.gracefulShutdownSupport = gracefulShutdownSupport;

        final long idleTimeoutMillis = config.idleTimeoutMillis();
        final long pingIntervalMillis = config.pingIntervalMillis();
        final long maxConnectionAgeMillis = config.maxConnectionAgeMillis();
        final int maxNumRequestsPerConnection = config.maxNumRequestsPerConnection();
        final boolean needsKeepAliveHandler = needsKeepAliveHandler(
                idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection);

        if (needsKeepAliveHandler) {
            keepAliveHandler = new Http2ServerKeepAliveHandler(
                    channel, encoder(), keepAliveTimer,
                    idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection);
        } else {
            keepAliveHandler = NoopKeepAliveHandler.INSTANCE;
        }

        requestDecoder = new Http2RequestDecoder(config, channel, encoder(), scheme, keepAliveHandler);
        connection().addListener(requestDecoder);
        decoder().frameListener(requestDecoder);

        // Setup post build options
        final long timeout = idleTimeoutMillis;
        if (timeout > 0) {
            gracefulShutdownTimeoutMillis(timeout);
        } else {
            // Timeout disabled
            gracefulShutdownTimeoutMillis(-1);
        }
    }

    @Override
    protected boolean needsImmediateDisconnection() {
        return gracefulShutdownSupport.isShuttingDown() ||
               requestDecoder.goAwayHandler().receivedErrorGoAway() || keepAliveHandler.isClosing();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        destroyKeepAliveHandler();
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
        destroyKeepAliveHandler();
        super.handlerRemoved0(ctx);
    }

    private void maybeInitializeKeepAliveHandler(ChannelHandlerContext ctx) {
        if (keepAliveHandler != NoopKeepAliveHandler.INSTANCE) {
            final Channel channel = ctx.channel();
            if (channel.isActive() && channel.isRegistered()) {
                keepAliveHandler.initialize(ctx);
            }
        }
    }

    private void destroyKeepAliveHandler() {
        keepAliveHandler.destroy();
    }

    KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }
}
