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

package com.linecorp.armeria.client;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.internal.Http2KeepAliveHandler;
import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.timeout.IdleStateEvent;

final class Http2ClientConnectionHandler extends AbstractHttp2ConnectionHandler {

    private final HttpClientFactory clientFactory;
    private final Http2ResponseDecoder responseDecoder;
    @Nullable
    private Http2KeepAliveHandler keepAlive;

    Http2ClientConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                 Http2Settings initialSettings, Channel channel,
                                 HttpClientFactory clientFactory) {

        super(decoder, encoder, initialSettings);
        this.clientFactory = clientFactory;

        if (Flags.useHttp2PingOnIdle()) {
            keepAlive = new Http2KeepAliveHandler(channel, encoder.frameWriter(), connection());
        }
        responseDecoder = new Http2ResponseDecoder(channel, encoder(), clientFactory, keepAlive);
        connection().addListener(responseDecoder);
        decoder().frameListener(responseDecoder);

        // Setup post build options
        final long timeout = clientFactory.idleTimeoutMillis();
        if (timeout > 0) {
            gracefulShutdownTimeoutMillis(timeout);
        } else {
            // Timeout disabled
            gracefulShutdownTimeoutMillis(-1);
        }
    }

    Http2ResponseDecoder responseDecoder() {
        return responseDecoder;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);

        // NB: Http2ConnectionHandler does not flush the preface string automatically.
        ctx.flush();
    }

    @Override
    protected boolean needsImmediateDisconnection() {
        return clientFactory.isClosing() || responseDecoder.goAwayHandler().receivedErrorGoAway();
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
}
