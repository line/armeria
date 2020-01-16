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

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.GenericFutureListener;

/*
 Idle time should always be less than pingtimeoutinnanos
 */
@NotThreadSafe
class Http2KeepAlivePinger {

    private static final Logger logger = LoggerFactory.getLogger(Http2KeepAlivePinger.class);
    private final Http2FrameWriter frameWriter;
    private long pingTimeoutInNanos;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private State state = State.NOT_STARTED;

    @Nullable
    private Future<?> shutdownFuture;
    @Nullable
    private ChannelFuture pingWriteFuture;
    private ChannelHandlerContext ctx;
    private final Runnable shutdownRunnable = () -> {
        final ChannelId id = ctx.channel().id();
        logger.trace("Closing channel: {} as PING timed out.", id);
        final ChannelFuture close = ctx.close();
        close.addListener(future -> {
            if (future.isSuccess()) {
                logger.trace("Closed channel: {} as PING timed out.", id);
            } else {
                logger.trace("Cannot close channel: {}", id, future.cause());
            }
        });
    };
    private final GenericFutureListener<ChannelFuture> pingWriteListener = future ->{
        final EventLoop el = future.channel().eventLoop();
        if (future.isSuccess()) {
            shutdownFuture = el.schedule(
                    shutdownRunnable, pingTimeoutInNanos, TimeUnit.NANOSECONDS);
            state = State.PENDING_ACK;
        }
    };

    Http2KeepAlivePinger(Http2FrameWriter frameWriter, long pingTimeoutInNanos) {
        this.frameWriter = frameWriter;
        this.pingTimeoutInNanos = pingTimeoutInNanos;
    }

    public void onChannelIdle(ChannelHandlerContext ctx, IdleStateEvent event) {
        if (event.state() != IdleState.ALL_IDLE) {
            // Only interested in ALL_IDLE event.
            return;
        }

        pingWriteFuture = frameWriter.writePing(ctx, false, random.nextLong(), ctx.newPromise())
                .addListener(pingWriteListener);
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
        // Is it costly to cancel?
        if (state != State.PENDING_ACK) {
            // we got ack for something not sent at the first place.
        }
        state = State.PING_ACK_RECEIVED;
        if (shutdownFuture != null) {
            shutdownFuture.cancel(false);
        }
    }

    private enum State {
        PENDING_ACK, PING_ACK_RECEIVED, NOT_STARTED
    }
}
