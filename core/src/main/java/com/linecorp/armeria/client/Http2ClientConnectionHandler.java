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

import static com.linecorp.armeria.internal.common.KeepAliveHandlerUtil.needsKeepAliveHandler;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;

final class Http2ClientConnectionHandler extends AbstractHttp2ConnectionHandler {

    private final Http2ResponseDecoder responseDecoder;

    Http2ClientConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                 Http2Settings initialSettings, Channel channel,
                                 HttpClientFactory clientFactory, SessionProtocol protocol) {

        super(decoder, encoder, initialSettings,
              newKeepAliveHandler(encoder, channel, clientFactory, protocol));

        responseDecoder = new Http2ResponseDecoder(channel, encoder(), clientFactory, keepAliveHandler());
        connection().addListener(responseDecoder);
        decoder().frameListener(responseDecoder);
    }

    private static KeepAliveHandler newKeepAliveHandler(
            Http2ConnectionEncoder encoder, Channel channel,
            HttpClientFactory clientFactory, SessionProtocol protocol) {

        final long idleTimeoutMillis = clientFactory.idleTimeoutMillis();
        final boolean keepAliveOnPing = clientFactory.keepAliveOnPing();
        final long pingIntervalMillis = clientFactory.pingIntervalMillis();
        final long maxConnectionAgeMillis = clientFactory.maxConnectionAgeMillis();
        final int maxNumRequestsPerConnection = clientFactory.maxNumRequestsPerConnection();
        final boolean needsKeepAliveHandler = needsKeepAliveHandler(
                idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection);

        if (!needsKeepAliveHandler) {
            return new NoopKeepAliveHandler();
        }

        final Timer keepAliveTimer =
                MoreMeters.newTimer(clientFactory.meterRegistry(), "armeria.client.connections.lifespan",
                                    ImmutableList.of(Tag.of("protocol", protocol.uriText())));
        return new Http2ClientKeepAliveHandler(
                channel, encoder.frameWriter(), keepAliveTimer,
                idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis, maxNumRequestsPerConnection,
                keepAliveOnPing);
    }

    Http2ResponseDecoder responseDecoder() {
        return responseDecoder;
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

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);

        super.channelActive(ctx);
        // NB: Http2ConnectionHandler does not flush the preface string automatically.
        ctx.flush();
    }

    @Override
    protected boolean needsImmediateDisconnection() {
        return responseDecoder.goAwayHandler().receivedErrorGoAway() ||
               keepAliveHandler().isClosing();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        destroyKeepAliveHandler();
        super.channelInactive(ctx);
    }

    private void maybeInitializeKeepAliveHandler(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            keepAliveHandler().initialize(ctx);
        }
    }

    private void destroyKeepAliveHandler() {
        keepAliveHandler().destroy();
    }
}
