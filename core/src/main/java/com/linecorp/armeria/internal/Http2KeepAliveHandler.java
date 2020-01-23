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

package com.linecorp.armeria.internal;

import static com.google.common.base.Preconditions.checkState;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * This will send {@link Http2PingFrame} when an {@link IdleStateEvent} is emitted by {@link IdleStateHandler}.
 * Specifically, it will write a PING frame to remote and then expects an ACK back within
 * configured {@code pingTimeoutInNanos}. If timeout exceeds then channel will be closed.
 *
 * <p>This class is <b>not</b> thread-safe and all methods are to be called from single thread such as
 * {@link EventLoop}.
 */
@NotThreadSafe
public class Http2KeepAliveHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http2KeepAliveHandler.class);

    private final Http2FrameWriter frameWriter;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private final Stopwatch stopwatch = Stopwatch.createUnstarted();

    @Nullable
    private ChannelFuture pingWriteFuture;
    @Nullable
    private Future<?> shutdownFuture;
    private long pingTimeoutInMs;
    private State state = State.IDLE;
    private Channel channel;
    private final Runnable shutdownRunnable = () -> {
        logger.debug("{} Closing channel due to PING timeout", channel);
        channel.close().addListener(future -> {
            if (future.isSuccess()) {
                logger.debug("{} Closed channel due to PING timeout", channel);
            } else {
                logger.debug("{} Channel cannot be closed", channel, future.cause());
            }
            state = State.SHUTDOWN;
        });
    };
    private final ChannelFutureListener pingWriteListener = future -> {
        if (future.isSuccess()) {
            final EventLoop el = channel.eventLoop();
            shutdownFuture = el.schedule(shutdownRunnable, pingTimeoutInMs, TimeUnit.MILLISECONDS);
            state = State.PENDING_PING_ACK;
            stopwatch.reset().start();
        } else {
            // Mostly because the channel is already closed.
            logger.debug("{} Channel PING write failed. Closing channel", channel);
            state = State.SHUTDOWN;
            channel.close();
        }
    };
    private long lastPingPayload;

    public Http2KeepAliveHandler(Channel channel, Http2FrameWriter frameWriter, long pingTimeoutInMs) {
        this.channel = channel;
        this.frameWriter = frameWriter;
        this.pingTimeoutInMs = pingTimeoutInMs;
    }

    private static void throwProtocolErrorException(String msg, Object... args) throws Http2Exception {
        throw new Http2Exception(Http2Error.PROTOCOL_ERROR, String.format(msg, args));
    }

    /**
     * Callback for when the channel is idle.
     * @throws IllegalStateException when subsequent {@link IdleStateEvent} is less than round trip time.
     *      For ex:
     *      <ol>
     *          <li>IdleStateEvent occurred.</li>
     *          <li>PING is sent to peer.</li>
     *          <li>IdleStateEvent occurred, before ACK is sent by peer.</li>
     *      </ol>
     */
    public void onChannelIdle(ChannelHandlerContext ctx, IdleStateEvent event) {
        checkState(state == State.IDLE, "Invalid state. Expecting IDLE but was %s", state);

        // Only interested in ALL_IDLE event.
        if (event.state() != IdleState.ALL_IDLE) {
            return;
        }

        logger.debug("{} {} event triggered on channel. Sending PING(ACK=0)", channel, event);
        writePing(ctx);
    }

    private void writePing(ChannelHandlerContext ctx) {
        lastPingPayload = random.nextLong();
        pingWriteFuture = frameWriter.writePing(ctx, false, lastPingPayload, ctx.newPromise())
                                     .addListener(pingWriteListener);
        state = State.PING_SCHEDULED;
        ctx.flush();
    }

    /**
     * Callback for when channel is in-active to cleans up resources.
     */
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

    /**
     * Validates the PING ACK.
     * @param data data received with the PING ACK
     * @throws Http2Exception when the PING ACK data does not match to PING data or
     *                        when a PING ACK is received without PING sent.
     */
    public void onPingAck(long data) throws Http2Exception {
        final long elapsed = stopwatch.elapsed(TimeUnit.NANOSECONDS);

        if (state != State.PENDING_PING_ACK) {
            throwProtocolErrorException("State expected PENDING_PING_ACK but is %s", state);
        }
        if (lastPingPayload != data) {
            throwProtocolErrorException("PING received but payload does not match. Expected %d Received %d",
                                        lastPingPayload, data);
        }
        if (shutdownFuture != null) {
            final boolean isCancelled = shutdownFuture.cancel(false);
            if (!isCancelled) {
                logger.debug("{} shutdownFuture cannot be cancelled because of late PING ACK", channel);
            }
        }
        logger.debug("{} Received PING(ACK=1) in {} ns", channel, elapsed);
        state = State.IDLE;
    }

    @VisibleForTesting
    State getState() {
        return state;
    }

    @VisibleForTesting
    long getLastPingPayload() {
        return lastPingPayload;
    }

    /**
     * State changes from IDLE -> PING_SCHEDULED -> PENDING_PING_ACK -> IDLE and so on.
     * When the channel is inactive then the state changes to SHUTDOWN.
     */
    enum State {
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
