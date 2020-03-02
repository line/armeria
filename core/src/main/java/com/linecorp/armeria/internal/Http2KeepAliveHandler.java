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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * This will send an {@link Http2PingFrame} when an {@link IdleStateEvent} is emitted by {@link
 * IdleStateHandler} and {@linkplain Flags#useHttp2PingOnIdle()} is true.
 *
 * <p>Once an {@link IdleStateEvent} is triggered and when there are active streams open then a
 * {@link Http2PingFrame} will be written on connection. When there are no active streams then it depends on
 * {@linkplain Flags#useHttp2PingOnNoActiveStreams()}.
 *
 * <p>Once a {@link Http2PingFrame} is written, then either an ACK for the {@link Http2PingFrame} or any data
 * is read on connection will invalidate the condition that triggers connection closure. If either of the
 * conditions are not met then the connection will be closed.
 *
 * <p>This class is <b>not</b> thread-safe and all methods are to be called from single thread such
 * as {@link EventLoop}.
 *
 * @see Flags#useHttp2PingOnIdle()
 * @see Flags#useHttp2PingOnNoActiveStreams()
 * @see Flags#defaultHttp2PingTimeoutMillis()
 */
@NotThreadSafe
public class Http2KeepAliveHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http2KeepAliveHandler.class);

    @Nullable
    private final Stopwatch stopwatch = logger.isDebugEnabled() ? Stopwatch.createUnstarted() : null;
    private final boolean sendPingsOnNoActiveStreams;
    private final long pingTimeoutMillis;
    private final Http2FrameWriter frameWriter;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private final Http2Connection http2Connection;
    private final Channel channel;
    private final ChannelFutureListener pingWriteListener = new PingWriteListener();
    private final Runnable shutdownRunnable = this::closeChannelAndLog;

    @Nullable
    private ChannelFuture pingWriteFuture;
    @Nullable
    private Future<?> shutdownFuture;
    private long lastPingPayload;
    private State state = State.IDLE;

    public Http2KeepAliveHandler(Channel channel, Http2FrameWriter frameWriter,
                                 Http2Connection http2Connection) {
        this(channel, frameWriter, http2Connection, Flags.defaultHttp2PingTimeoutMillis(),
             Flags.useHttp2PingOnNoActiveStreams());
    }

    public Http2KeepAliveHandler(Channel channel, Http2FrameWriter frameWriter, Http2Connection http2Connection,
                                 long pingTimeoutMillis, boolean sendPingsOnNoActiveStreams) {
        checkArgument(pingTimeoutMillis > 0, pingTimeoutMillis);
        this.channel = requireNonNull(channel, "channel");
        this.frameWriter = requireNonNull(frameWriter, "frameWriter");
        this.pingTimeoutMillis = pingTimeoutMillis;
        this.http2Connection = requireNonNull(http2Connection, "http2Connection");
        this.sendPingsOnNoActiveStreams = sendPingsOnNoActiveStreams;
    }

    public void onChannelIdle(ChannelHandlerContext ctx, IdleStateEvent event) {
        logger.debug("{} {} triggered.", channel, event);

        if (!canSendPing()) {
            // The default behaviour is to shutdown the channel on idle timeout if not HTTP 2.0 conn.
            // So preserving the behaviour.
            closeChannelAndLog();
            return;
        }

        // Only interested in ALL_IDLE event and when Http2KeepAliveHandler is ready.
        // Http2KeepAliveHandler may not be ready because it is currently handling
        // sending a PING or expecting a PING ACK on channel.
        if (state != State.IDLE || event.state() != IdleState.ALL_IDLE) {
            return;
        }

        writePing(ctx);
    }

    private boolean canSendPing() {
        if (http2Connection.numActiveStreams() == 0) {
            return sendPingsOnNoActiveStreams;
        } else {
            return true;
        }
    }

    private void writePing(ChannelHandlerContext ctx) {
        lastPingPayload = random.nextLong();
        state = State.PING_SCHEDULED;
        pingWriteFuture = frameWriter.writePing(ctx, false, lastPingPayload, ctx.newPromise())
                                     .addListener(pingWriteListener);
        ctx.flush();
    }

    /**
     * Callback for when channel is in-active to cleans up resources.
     */
    public void onChannelInactive() {
        state = State.SHUTDOWN;
        cancelFutures();
    }

    public void onChannelRead() {
        state = State.IDLE;
        cancelFutures();
    }

    private void cancelFutures() {
        if (shutdownFuture != null) {
            shutdownFuture.cancel(false);
            shutdownFuture = null;
        }
        if (pingWriteFuture != null) {
            pingWriteFuture.cancel(false);
            pingWriteFuture = null;
        }
    }

    public void onPingAck(long data) {
        final long elapsed = getStopwatchElapsedInNanos();
        if (!isGoodPingAck(data)) {
            return;
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

    private boolean isGoodPingAck(long data) {
        // This condition can be true when channel read some data other than PING ACK frame
        // or a PING ACK is received without sending PING in first place.
        if (state != State.PENDING_PING_ACK) {
            logger.debug("{} Ignoring PING(ACK=1, DATA={}) received", channel, data);
            return false;
        }
        if (lastPingPayload != data) {
            logger.debug("{} Unexpected PING(ACK=1, DATA={}) received, " +
                         "but expecting PING(ACK=1, DATA={})", channel, data, lastPingPayload);
            return false;
        }
        return true;
    }

    @VisibleForTesting
    State state() {
        return state;
    }

    @VisibleForTesting
    long lastPingPayload() {
        return lastPingPayload;
    }

    private void closeChannelAndLog() {
        if (state == State.SHUTDOWN) {
            return;
        }
        logger.debug("{} Closing an idle channel", channel);
        channel.close().addListener(future -> {
            if (future.isSuccess()) {
                logger.debug("{} Closed an channel", channel);
            } else {
                logger.debug("{} Failed to close an idle channel", channel, future.cause());
            }
            state = State.SHUTDOWN;
        });
    }

    private long getStopwatchElapsedInNanos() {
        if (stopwatch == null) {
            return -1;
        }
        return stopwatch.elapsed(TimeUnit.NANOSECONDS);
    }

    /**
     * State changes from IDLE -> PING_SCHEDULED -> PENDING_PING_ACK -> IDLE and so on. When the
     * channel is inactive then the state changes to SHUTDOWN.
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

    private class PingWriteListener implements ChannelFutureListener {

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
                final EventLoop el = channel.eventLoop();
                shutdownFuture = el.schedule(shutdownRunnable, pingTimeoutMillis, TimeUnit.MILLISECONDS);
                state = State.PENDING_PING_ACK;
                resetStopwatch();
            } else {
                // Mostly because the channel is already closed. So ignore and change state to IDLE.
                // If the channel is closed, we change state to SHUTDOWN on onChannelInactive.
                if (Exceptions.isExpected(future.cause())) {
                    logger.debug("{} Channel PING write failed", channel, future.cause());
                }
                if (state != State.SHUTDOWN) {
                    state = State.IDLE;
                }
            }
        }

        private void resetStopwatch() {
            if (stopwatch != null) {
                stopwatch.reset().start();
            }
        }
    }
}
