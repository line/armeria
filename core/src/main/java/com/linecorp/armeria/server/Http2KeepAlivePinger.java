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

import static com.google.common.base.Preconditions.checkState;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * This will send {@link Http2PingFrame} when an {@link IdleStateEvent} is emitted by {@link IdleStateHandler}.
 * Specifically, it will write a PING frame to remote and then expects an ACK back within
 * configured pingTimeOut. If timeout exceeds then channel will be closed.
 *</p>
 * This constructor will fail to initialize when pipeline does not have {@link IdleStateHandler}.
 *</p>
 * This class is not thread-safe and all methods are to be called from single thread such as {@link EventLoop}.
 */
@NotThreadSafe
class Http2KeepAlivePinger {

    private static final Logger logger = LoggerFactory.getLogger(Http2KeepAlivePinger.class);

    private final Http2FrameWriter frameWriter;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private final Stopwatch stopwatch = Stopwatch.createUnstarted();

    private ChannelHandlerContext ctx;
    private Runnable shutdownRunnable = () -> {
        final Channel channel = ctx.channel();
        logger.trace("Closing channel: {} as PING timed out.", channel);
        final ChannelFuture close = ctx.close();
        close.addListener(future -> {
            if (future.isSuccess()) {
                logger.trace("Closed channel: {} as PING timed out.", channel);
            } else {
                logger.trace("Cannot close channel: {}.", channel, future.cause());
            }
        });
    };
    private long pingTimeoutInNanos;
    private State state = State.IDLE;
    @Nullable
    private Future<?> shutdownFuture;
    @Nullable
    private ChannelFuture pingWriteFuture;
    private final GenericFutureListener<ChannelFuture> pingWriteListener = future -> {
        final EventLoop el = future.channel().eventLoop();
        if (future.isSuccess()) {
            shutdownFuture = el.schedule(
                    shutdownRunnable, pingTimeoutInNanos, TimeUnit.NANOSECONDS);
            state = State.PENDING_PING_ACK;
            stopwatch.reset().start();
        } else {
            // write failed, likely the channel is closed.
            logger.trace("PING write failed for channel: {}", ctx.channel());
        }
    };
    private long lastPingPayload;

    Http2KeepAlivePinger(ChannelHandlerContext ctx, Http2FrameWriter frameWriter, long pingTimeoutInNanos) {
        checkState(ctx.channel().pipeline().get(IdleStateHandler.class) != null,
                   "Expecting IdleStateHandler to be in pipeline, but not found");
        this.ctx = ctx;
        this.frameWriter = frameWriter;
        this.pingTimeoutInNanos = pingTimeoutInNanos;
    }

    public void onChannelIdle(IdleStateEvent event) {
        // Check if we are PENDING_PING. If not then one of this is true:
        // (1) Channel has pending ACK. (2) Channel is already shutdown.
        checkState(state == State.IDLE, "");
        if (event.state() != IdleState.ALL_IDLE) {
            // Only interested in ALL_IDLE event.
            return;
        }

        lastPingPayload = random.nextLong();
        pingWriteFuture = frameWriter.writePing(ctx, false, lastPingPayload, ctx.newPromise())
                                     .addListener(pingWriteListener);
    }

    public void onChannelInactive() {
        state = State.SHUTDOWN;
        if (shutdownFuture != null) {
            shutdownFuture.cancel(false);
            shutdownFuture = null;
        }
        if (pingWriteFuture != null) {
            pingWriteFuture.cancel(false);
            pingWriteFuture = null;
        }
    }

    public void onPingAck(long data) throws Http2Exception {
        final long elapsed = stopwatch.elapsed(TimeUnit.NANOSECONDS);
        if (state != State.PENDING_PING_ACK) {
            // we got ACK for something not sent at the first place.
        }
        if (lastPingPayload != data) {
            throw new Http2Exception(Http2Error.PROTOCOL_ERROR);
        }
        if (shutdownFuture != null) {
            shutdownFuture.cancel(false);
        }
        logger.trace("Channel: {} received PING(ACK=1) in {}ns.", elapsed, ctx.channel());
        state = State.IDLE;
    }

    private enum State {
        /* Waiting for IdleStateEvent so we can write PING */
        IDLE,

        /* PING is sent and is pending ACK */
        PENDING_PING_ACK,

        /* Not active anymore */
        SHUTDOWN
    }
}
