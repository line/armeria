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

package com.linecorp.armeria.server;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;

class DefaultPingHandler implements PingHandler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPingHandler.class);
    private final Http2FrameWriter frameWriter;
    final Http2PingRequestResponsePair pair = new Http2PingRequestResponsePair();
    final Random random = new Random();

    DefaultPingHandler(final Http2FrameWriter frameWriter) {
        this.frameWriter = frameWriter;
    }

    @Override
    public void start(ChannelHandlerContext ctx) {
        final Channel channel = ctx.channel();
        final EventLoop eventLoop = channel.eventLoop();
        final ChannelPromise pingWritePromise = ctx.newPromise();
        final Http2PingFrame newPingFrame = getNewPingFrame();
        pingWritePromise.addListener(future -> pair.setRequestFrame(newPingFrame));

        eventLoop.scheduleAtFixedRate(
                () -> frameWriter.writePing(ctx, newPingFrame.ack(), newPingFrame.content(), pingWritePromise),
                1000,5000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        pair.setResponseFrame(new DefaultHttp2PingFrame(data, true));
        logger.trace("PING ACK received");
    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        frameWriter.writePing(ctx, true, data, ctx.newPromise().addListener(future ->
            logger.trace("received a PING from client and responding with PING for channel: {}",
                         ctx.channel().id())
        ));
    }

    public Http2PingFrame getNewPingFrame() {
        return new DefaultHttp2PingFrame(random.nextLong());
    }
}
