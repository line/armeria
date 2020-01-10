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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkState;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.util.concurrent.Future;

/**
 * Default implementation of {@link PingHandler}.
 */
@NotThreadSafe
public class DefaultPingHandler implements PingHandler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPingHandler.class);

    private final Http2FrameWriter frameWriter;
    private final Http2PingRequestResponsePair pair = new Http2PingRequestResponsePair();
    private final Random random = new Random();

    @Nullable
    private Future<?> pingTimeoutFuture;
    @Nullable
    private Future<?> pingWriteFuture;

    private boolean started;

    /**
     * Handles ping event. TODO detailed javadoc.
     */
    public DefaultPingHandler(final Http2FrameWriter frameWriter) {
        this.frameWriter = frameWriter;
    }

    @Override
    public void start(ChannelHandlerContext ctx) {
        checkState(!started, "DefaultPingHandler already started.");

        started = true;
        schedulePingWrite(ctx);
    }

    private void schedulePingWrite(ChannelHandlerContext ctx) {
        checkIfAlreadyStarted();

        final Channel channel = ctx.channel();
        final EventLoop eventLoop = channel.eventLoop();
        final Http2PingFrame newPingFrame = getNewPingFrame();

        // TODO move to ServerConfig.
        final long pingDelay = 5000;
        final long pingAckTimeout = 1000;

        pingWriteFuture = eventLoop.schedule(
                () -> frameWriter.writePing(ctx, newPingFrame.ack(), newPingFrame.content(), ctx.newPromise()),
                pingDelay, TimeUnit.MILLISECONDS)
                                   .addListener(f -> pair.setRequestFrame(newPingFrame))
                                   .addListener(f -> {
                                       if (f.isSuccess()) {
                                           pingTimeoutFuture = eventLoop.schedule(() -> {
                                               ctx.fireUserEventTriggered(
                                                       new Http2PingTimeoutEvent()
                                               );
                                           }, pingAckTimeout, TimeUnit.SECONDS);
                                       } else {
                                           // TODO send WriteFailed event instead of Object
                                           ctx.fireUserEventTriggered(new Object());
                                       }
                                   });
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        checkIfAlreadyStarted();

        logger.trace("PING ACK received {}", data);
        if (pingTimeoutFuture != null) {
            pingTimeoutFuture.cancel(false);
        }
        pair.setResponseFrame(new DefaultHttp2PingFrame(data, true));
        schedulePingWrite(ctx);
    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        // Notes: Http2ConnectionHandler by default send ACK when PING is received.
        // So do not have write PING ACK here. But usefult for debugging purpose.
        logger.trace("received a PING {} from client and " +
                     "responding with PING for channel: {}",
                     data,
                     ctx.channel().id());
    }

    private void checkIfAlreadyStarted() {
        checkState(started, "DefaultPingHandler already started.");
    }

    @Override
    public void stop() {
        if (pingTimeoutFuture != null) {
            pingTimeoutFuture.cancel(false);
        }
        if (pingWriteFuture != null) {
            pingWriteFuture.cancel(false);
        }
    }

    private Http2PingFrame getNewPingFrame() {
        return new DefaultHttp2PingFrame(random.nextLong());
    }
}
