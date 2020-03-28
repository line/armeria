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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

import com.linecorp.armeria.common.util.Exceptions;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;

/**
 * A {@link IdleTimeoutScheduler} that writes a PING when neither read nor write was performed for
 * the specified {@code pingIntervalMillis}, and closes the connection
 * when neither read nor write was performed within the given {@code idleTimeoutMillis}.
 */
public abstract class KeepAliveHandler extends IdleTimeoutScheduler {

    private static final Logger logger = LoggerFactory.getLogger(KeepAliveHandler.class);

    @Nullable
    private final Stopwatch stopwatch = logger.isDebugEnabled() ? Stopwatch.createUnstarted() : null;
    private final long pingIntervalMillis;
    private final Channel channel;
    private final String name;
    private final ChannelFutureListener pingWriteListener = new PingWriteListener();
    private final Runnable shutdownRunnable = this::closeChannelAndLog;

    @Nullable
    private ChannelFuture pingWriteFuture;
    @Nullable
    private Future<?> shutdownFuture;
    private State state = State.IDLE;

    protected KeepAliveHandler(Channel channel, String name, long idleTimeoutMillis, long pingIntervalMillis) {
        super(idleTimeoutMillis, pingIntervalMillis, TimeUnit.MILLISECONDS, channel.eventLoop());
        this.channel = channel;
        this.name = name;
        this.pingIntervalMillis = pingIntervalMillis;
    }

    @Override
    public void destroy() {
        super.destroy();
        state = State.SHUTDOWN;
        cancelFutures();
    }

    @Override
    public final void onReadOrWrite() {
        if (state == State.SHUTDOWN) {
            return;
        }
        super.onReadOrWrite();
        state = State.IDLE;
        cancelFutures();
    }

    @Override
    public final void onPing() {
        if (state == State.SHUTDOWN) {
            return;
        }
        super.onPing();
        state = State.IDLE;
        cancelFutures();
    }

    protected abstract ChannelFuture writePing(ChannelHandlerContext ctx);

    protected abstract boolean hasRequestsInProgress(ChannelHandlerContext ctx);

    @Override
    protected void onIdleEvent(ChannelHandlerContext ctx, IdleStateEvent evt) {
        if (evt.state() == IdleState.ALL_IDLE && evt.isFirst()) {
            if (!hasRequestsInProgress(ctx)) {
                state = State.SHUTDOWN;
                logger.debug("{} Closing an idle {} connection", ctx.channel(), name);
                ctx.channel().close();
            }
            return;
        }

        if (pingIntervalMillis > 0 && evt.state() == IdleState.PING_IDLE && evt.isFirst()) {
            state = State.PING_SCHEDULED;
            writePing(ctx).addListener(pingWriteListener);
        }
    }

    protected final Future<?> shutdownFuture() {
        return shutdownFuture;
    }

    protected final State state() {
        return state;
    }

    protected final boolean isPendingPingAck() {
        return state == State.PENDING_PING_ACK;
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

    private void closeChannelAndLog() {
        if (state == State.SHUTDOWN) {
            return;
        }
        logger.debug("{} Closing an idle channel", channel);
        channel.close().addListener(future -> {
            if (future.isSuccess()) {
                logger.debug("{} Closed an idle channel", channel);
            } else {
                logger.debug("{} Failed to close an idle channel", channel, future.cause());
            }
            state = State.SHUTDOWN;
        });
    }

    /**
     * State changes from IDLE -> PING_SCHEDULED -> PENDING_PING_ACK -> IDLE and so on. When the
     * channel is inactive then the state changes to SHUTDOWN.
     */
    @VisibleForTesting
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
                logger.debug("{} PING write successful", channel);
                final EventLoop el = channel.eventLoop();
                shutdownFuture = el.schedule(shutdownRunnable, pingIntervalMillis, TimeUnit.MILLISECONDS);
                state = State.PENDING_PING_ACK;
                resetStopwatch();
            } else {
                // Mostly because the channel is already closed. So ignore and change state to IDLE.
                // If the channel is closed, we change state to SHUTDOWN on destroy.
                if (!future.isCancelled() && Exceptions.isExpected(future.cause())) {
                    logger.debug("{} PING write failed", channel, future.cause());
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
