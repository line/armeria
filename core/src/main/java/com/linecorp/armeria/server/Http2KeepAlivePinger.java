/*
 * Copyright 2017 LINE Corporation
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

import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.util.concurrent.GenericFutureListener;

class Http2KeepAlivePinger {

    private static final Logger logger = LoggerFactory.getLogger(Http2KeepAlivePinger.class);
    private Http2FrameWriter frameWriter;
    private long pingTimeoutInNanos;
    private ThreadLocalRandom random;
    private Stopwatch stopwatch;
    private long pingWrittenTime;
    private Future<?> shutdownFuture;
    private ChannelFuture pingWriteFuture;
    private ChannelHandlerContext ctx;

    private Runnable shutdownRunnable = () -> {
        logger.trace("Closing channel: {} as PING timed out.", ctx.channel().id());
        final ChannelFuture close = ctx.close();
        close.addListener(future -> {
            if (future.isSuccess()) {
                logger.trace("Closed channel as PING timed out.");
            } else {
                ctx.fireExceptionCaught(future.cause());
            }
        });
    };

    private GenericFutureListener<ChannelFuture> pingWriteFutureListener = future -> {
        if (future.isSuccess()) {
            shutdownFuture = ctx.executor().schedule(
                    shutdownRunnable, pingTimeoutInNanos, TimeUnit.NANOSECONDS);
            stopwatch.reset();
        } else {
            // Likely closed.
        }
    };

    public void onChannelIdle(ChannelHandlerContext ctx) {
        final long nano = stopwatch.elapsed().toNanos();
        final Channel channel;
        pingWriteFuture = frameWriter.writePing(ctx, false, random.nextLong(), ctx.newPromise())
                                     .addListener(pingWriteFutureListener);
    }

    public void onChannelInactive(ChannelHandlerContext ctx) {
        if (shutdownFuture != null) {
            shutdownFuture.cancel(false);
        }
        if (pingWriteFuture != null) {
            pingWriteFuture.cancel(false);
        }
    }

    public void onPingAck() {

    }

    private void closeChannel() {

    }

}
