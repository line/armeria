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
class Http2KeepAliveHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http2KeepAliveHandler.class);

    private final Http2FrameWriter frameWriter;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private final Stopwatch stopwatch = Stopwatch.createUnstarted();

    @Nullable
    private ChannelFuture pingWriteFuture;
    @Nullable
    private Future<?> shutdownFuture;
    private long pingTimeoutInNanos;
    private State state = State.IDLE;
    private Channel channel;
    private long lastPingPayload;

    private final Runnable shutdownRunnable = () -> {
        logger.debug("Closing channel: {} as PING timed out.", channel);
        final ChannelFuture close = channel.close();
        close.addListener(future -> {
            if (future.isSuccess()) {
                logger.debug("Closed channel: {} as PING timed out.", channel);
            } else {
                logger.debug("Cannot close channel: {}.", channel, future.cause());
            }
        });
    };

    private final GenericFutureListener<ChannelFuture> pingWriteListener = future -> {
        final EventLoop el = future.channel().eventLoop();
        if (future.isSuccess()) {
            shutdownFuture = el.schedule(shutdownRunnable, pingTimeoutInNanos, TimeUnit.NANOSECONDS);
            state = State.PENDING_PING_ACK;
            stopwatch.reset().start();
        } else {
            // write failed, likely the channel is closed.
            logger.debug("PING write failed for channel: {}", channel);
        }
    };

    Http2KeepAliveHandler(Channel channel, Http2FrameWriter frameWriter, long pingTimeoutInNanos) {
        this.channel = channel;
        this.frameWriter = frameWriter;
        this.pingTimeoutInNanos = pingTimeoutInNanos;
    }

    public void onChannelIdle(ChannelHandlerContext ctx, IdleStateEvent event) {
        checkState(state == State.IDLE, "Waiting for PING ACK or shutdown");

        // Only interested in ALL_IDLE event.
        if (event.state() != IdleState.ALL_IDLE) {
            return;
        }

        logger.debug("{} event triggered on channel: {}. Sending PING", event, channel);
        writePing(ctx);
    }

    private void writePing(ChannelHandlerContext ctx) {
        lastPingPayload = random.nextLong();
        pingWriteFuture = frameWriter.writePing(ctx, false, lastPingPayload, ctx.newPromise())
                                     .addListener(pingWriteListener);
        state = State.PING_SCHEDULED;
        ctx.flush();
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
            throw new Http2Exception(Http2Error.PROTOCOL_ERROR,
                                     "State expected PENDING_PING_ACK but is " + state);
        }
        if (lastPingPayload != data) {
            throw new Http2Exception(Http2Error.PROTOCOL_ERROR,
                                     "PING received but payload does not match. " + "Expected: " +
                                     lastPingPayload + ' ' + "Received :" + data);
        }
        if (shutdownFuture != null) {
            shutdownFuture.cancel(false);
        }
        logger.debug("Channel: {} received PING(ACK=1) in {}ns.", channel, elapsed);
        state = State.IDLE;
    }

    /**
     * State changes from IDLE -> PING_SCHEDULED -> PENDING_PING_ACK - IDLE and so on.
     * When the channel is inactive then the state changes to SHUTDOWN.
     */
    private enum State {
        /* Nothing happening, but waiting for IdleStateEvent */
        IDLE,

        /* PING is scheduled */
        PING_SCHEDULED,

        /* PING is sent and is pending ACK */
        PENDING_PING_ACK,

        /* Not active anymore */
        SHUTDOWN
    }
}
