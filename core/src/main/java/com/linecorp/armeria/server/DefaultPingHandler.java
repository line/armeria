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

import static com.google.common.base.Preconditions.checkState;

import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;

class DefaultPingHandler implements PingHandler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPingHandler.class);

    private final Random random = new Random();
    private final Http2FrameWriter frameWriter;
    private boolean isPingWritten;
    private Http2PingFrame lastWritePingFrame;
    private Future<?> timeoutFuture;

    DefaultPingHandler(final Http2FrameWriter frameWriter) {
        this.frameWriter = frameWriter;
    }

    @Override
    public void schedulePingWrite(ChannelHandlerContext ctx) {
        final Channel channel = ctx.channel();
        final EventLoop eventLoop = channel.eventLoop();
        lastWritePingFrame = new DefaultHttp2PingFrame(random.nextLong());
        eventLoop.scheduleAtFixedRate()
        eventLoop.schedule(() -> frameWriter.writePing(ctx, lastWritePingFrame.ack(),
                                                       lastWritePingFrame.content(),
                                                       ctx.voidPromise())
                                            .addListener(future -> timeoutFuture = eventLoop.schedule(
                                                  () -> ctx.fireUserEventTriggered(new Http2PingTimeoutEvent()),
                                                  0L, TimeUnit.MILLISECONDS)),
                          0L, TimeUnit.MILLISECONDS);
        isPingWritten = true;
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        checkState(isPingWritten, "PING ACK received without sending PING");

        if (lastWritePingFrame.content() != data) {
            throw new Http2Exception(Http2Error.PROTOCOL_ERROR);
        }

        timeoutFuture.cancel(false);
        isPingWritten = false;
        schedulePingWrite(ctx);
        logger.trace("PING ACK received");
    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        frameWriter.writePing(ctx, true, data, new DefaultChannelPromise(ctx.channel()).addListener(future -> {
            // may be replace with a proper listener instead of logger. Should log there. TODO
            logger.trace("received a PING from client and responding with PING for channel: {}",
                         ctx.channel().id());
        }));
    }
}
