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

import static com.linecorp.armeria.common.Flags.defaultUseHttp2PingOnIdleConnection;

import javax.annotation.Nullable;

import com.linecorp.armeria.internal.Http2KeepAliveHandler;
import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.common.IdleTimeoutHandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.timeout.IdleStateEvent;

final class Http2ServerConnectionHandler extends AbstractHttp2ConnectionHandler {

    private final GracefulShutdownSupport gracefulShutdownSupport;
    private final Http2RequestDecoder requestDecoder;

    @Nullable
    private Http2KeepAliveHandler keepAlive;

    Http2ServerConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                 Http2Settings initialSettings, Channel channel, ServerConfig config,
                                 GracefulShutdownSupport gracefulShutdownSupport, String scheme) {

        super(decoder, encoder, initialSettings);
        this.gracefulShutdownSupport = gracefulShutdownSupport;

        if (defaultUseHttp2PingOnIdleConnection()) {
            keepAlive = new Http2KeepAliveHandler(channel, encoder().frameWriter(), connection());
        }
        requestDecoder = new Http2RequestDecoder(config, channel, encoder(), scheme, keepAlive);
        connection().addListener(requestDecoder);
        decoder().frameListener(requestDecoder);

        // Setup post build options
        final long timeout = config.idleTimeoutMillis();
        if (timeout > 0) {
            gracefulShutdownTimeoutMillis(timeout);
        } else {
            // Timeout disabled
            gracefulShutdownTimeoutMillis(-1);
        }
    }

    @Override
    protected boolean needsImmediateDisconnection() {
        return gracefulShutdownSupport.isShuttingDown() || requestDecoder.goAwayHandler().receivedErrorGoAway();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        if (keepAlive != null) {
            keepAlive.onChannelInactive();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            if (keepAlive != null) {
                keepAlive.onChannelIdle(ctx, (IdleStateEvent) evt);
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        changeIdleStateHandlerToHttp2(ctx);
    }

    /**
     * When a client upgrades the connection from HTTP/1.1 to HTTP/2, then change the
     * {@link IdleTimeoutHandler#setHttp2(boolean)} to true so we can send PING's.
     */
    private static void changeIdleStateHandlerToHttp2(ChannelHandlerContext ctx) {
        final IdleTimeoutHandler idleTimeoutHandler = ctx.pipeline().get(
                IdleTimeoutHandler.class);
        if (idleTimeoutHandler == null) {
            // Means that config.idleTimeoutMillis() < 0; So ignore.
            return;
        }
        idleTimeoutHandler.setHttp2(true);
    }
}
