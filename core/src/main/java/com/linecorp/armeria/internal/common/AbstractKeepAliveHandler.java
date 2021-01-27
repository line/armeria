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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

import com.linecorp.armeria.common.util.Exceptions;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;

/**
 * An {@link AbstractKeepAliveHandler} that writes a PING when neither read nor write was performed for
 * the specified {@code pingIntervalMillis}, and closes the connection
 * when neither read nor write was performed within the given {@code idleTimeoutMillis}.
 */
public abstract class AbstractKeepAliveHandler implements KeepAliveHandler {

    private static final Logger logger = LoggerFactory.getLogger(AbstractKeepAliveHandler.class);

    @Nullable
    private final Stopwatch stopwatch = logger.isDebugEnabled() ? Stopwatch.createUnstarted() : null;
    private final ChannelFutureListener pingWriteListener = new PingWriteListener();
    private final Runnable shutdownRunnable = this::closeChannelAndLog;

    private final Channel channel;
    private final String name;
    private final boolean isServer;
    private final Timer keepAliveTimer;

    private final long maxNumRequestsPerConnection;
    private long currentNumRequests;

    @Nullable
    private ScheduledFuture<?> connectionIdleTimeout;
    private final long connectionIdleTimeNanos;
    private long lastConnectionIdleTime;

    @Nullable
    private ScheduledFuture<?> pingIdleTimeout;
    private final long pingIdleTimeNanos;
    private long lastPingIdleTime;
    private boolean firstPingIdleEvent = true;

    @Nullable
    private ScheduledFuture<?> maxConnectionAgeFuture;
    private final long maxConnectionAgeNanos;
    private boolean isMaxConnectionAgeExceeded;

    private boolean isInitialized;
    private PingState pingState = PingState.IDLE;

    @Nullable
    private ChannelFuture pingWriteFuture;
    @Nullable
    private Future<?> shutdownFuture;

    protected AbstractKeepAliveHandler(Channel channel, String name, Timer keepAliveTimer,
                                       long idleTimeoutMillis, long pingIntervalMillis,
                                       long maxConnectionAgeMillis, long maxNumRequestsPerConnection) {
        this.channel = channel;
        this.name = name;
        isServer = "server".equals(name);
        this.keepAliveTimer = keepAliveTimer;
        this.maxNumRequestsPerConnection = maxNumRequestsPerConnection;

        if (idleTimeoutMillis <= 0) {
            connectionIdleTimeNanos = 0;
        } else {
            connectionIdleTimeNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis);
        }

        if (pingIntervalMillis <= 0) {
            pingIdleTimeNanos = 0;
        } else {
            pingIdleTimeNanos = TimeUnit.MILLISECONDS.toNanos(pingIntervalMillis);
        }

        if (maxConnectionAgeMillis <= 0) {
            maxConnectionAgeNanos = 0;
        } else {
            maxConnectionAgeNanos = TimeUnit.MILLISECONDS.toNanos(maxConnectionAgeMillis);
        }
    }

    @Override
    public final void initialize(ChannelHandlerContext ctx) {
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        if (isInitialized) {
            return;
        }
        isInitialized = true;

        final long connectionStartTimeNanos = System.nanoTime();
        ctx.channel().closeFuture().addListener(unused -> {
            keepAliveTimer.record(System.nanoTime() - connectionStartTimeNanos, TimeUnit.NANOSECONDS);
        });

        lastConnectionIdleTime = lastPingIdleTime = connectionStartTimeNanos;
        if (connectionIdleTimeNanos > 0) {
            connectionIdleTimeout = executor().schedule(new ConnectionIdleTimeoutTask(ctx),
                                                        connectionIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (pingIdleTimeNanos > 0) {
            pingIdleTimeout = executor().schedule(new PingIdleTimeoutTask(ctx),
                                                  pingIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (maxConnectionAgeNanos > 0) {
            maxConnectionAgeFuture = executor().schedule(new MaxConnectionAgeExceededTask(ctx),
                                                         maxConnectionAgeNanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public final void destroy() {
        isInitialized = true;
        if (connectionIdleTimeout != null) {
            connectionIdleTimeout.cancel(false);
            connectionIdleTimeout = null;
        }
        if (pingIdleTimeout != null) {
            pingIdleTimeout.cancel(false);
            pingIdleTimeout = null;
        }
        if (maxConnectionAgeFuture != null) {
            maxConnectionAgeFuture.cancel(false);
            maxConnectionAgeFuture = null;
        }
        pingState = PingState.SHUTDOWN;
        isMaxConnectionAgeExceeded = true;
        cancelFutures();
    }

    @Override
    public final void onReadOrWrite() {
        if (pingState == PingState.SHUTDOWN) {
            return;
        }

        if (connectionIdleTimeNanos > 0) {
            lastConnectionIdleTime = System.nanoTime();
        }

        if (pingResetsPreviousPing()) {
            if (pingIdleTimeNanos > 0) {
                lastPingIdleTime = System.nanoTime();
                firstPingIdleEvent = true;
            }
            pingState = PingState.IDLE;
            cancelFutures();
        }
    }

    @Override
    public final void onPing() {
        if (pingState == PingState.SHUTDOWN) {
            return;
        }

        if (pingIdleTimeNanos > 0) {
            firstPingIdleEvent = true;
            lastPingIdleTime = System.nanoTime();
        }
        pingState = PingState.IDLE;
        cancelFutures();
    }

    @Override
    public final boolean isClosing() {
        return pingState == PingState.SHUTDOWN;
    }

    @Override
    public final boolean needToCloseConnection() {
        return isMaxConnectionAgeExceeded || (currentNumRequests > 0 && currentNumRequests >=
                                                                        maxNumRequestsPerConnection);
    }

    @Override
    public final void increaseNumRequests() {
        if (maxNumRequestsPerConnection == 0) {
            return;
        }
        currentNumRequests++;
    }

    protected abstract ChannelFuture writePing(ChannelHandlerContext ctx);

    protected abstract boolean pingResetsPreviousPing();

    protected abstract boolean hasRequestsInProgress(ChannelHandlerContext ctx);

    @Nullable
    protected final Future<?> shutdownFuture() {
        return shutdownFuture;
    }

    protected final boolean isPendingPingAck() {
        return pingState == PingState.PENDING_PING_ACK;
    }

    @VisibleForTesting
    final PingState state() {
        return pingState;
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
        if (pingState == PingState.SHUTDOWN) {
            return;
        }
        logger.debug("{} Closing an idle channel", channel);
        pingState = PingState.SHUTDOWN;
        channel.close().addListener(future -> {
            if (future.isSuccess()) {
                logger.debug("{} Closed an idle channel", channel);
            } else {
                logger.debug("{} Failed to close an idle channel", channel, future.cause());
            }
        });
    }

    private ScheduledExecutorService executor() {
        return channel.eventLoop();
    }

    /**
     * State changes from IDLE -> PING_SCHEDULED -> PENDING_PING_ACK -> IDLE and so on. When the
     * channel is inactive then the state changes to SHUTDOWN.
     */
    @VisibleForTesting
    enum PingState {
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
                shutdownFuture = el.schedule(shutdownRunnable, pingIdleTimeNanos, TimeUnit.NANOSECONDS);
                pingState = PingState.PENDING_PING_ACK;
                resetStopwatch();
            } else {
                // Mostly because the channel is already closed. So ignore and change state to IDLE.
                // If the channel is closed, we change state to SHUTDOWN on destroy.
                if (!future.isCancelled() && Exceptions.isExpected(future.cause())) {
                    logger.debug("{} PING write failed", channel, future.cause());
                }
                if (pingState != PingState.SHUTDOWN) {
                    pingState = PingState.IDLE;
                }
            }
        }

        private void resetStopwatch() {
            if (stopwatch != null) {
                stopwatch.reset().start();
            }
        }
    }

    private abstract static class AbstractKeepAliveTask implements Runnable {

        private final ChannelHandlerContext ctx;

        AbstractKeepAliveTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            if (!ctx.channel().isOpen()) {
                return;
            }

            run(ctx);
        }

        protected abstract void run(ChannelHandlerContext ctx);
    }

    private final class ConnectionIdleTimeoutTask extends AbstractKeepAliveTask {

        private boolean warn;

        ConnectionIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {

            final long lastConnectionIdleTime = AbstractKeepAliveHandler.this.lastConnectionIdleTime;
            final long nextDelay;
            nextDelay = connectionIdleTimeNanos - (System.nanoTime() - lastConnectionIdleTime);
            if (nextDelay <= 0) {
                // Both reader and writer are idle - set a new timeout and
                // notify the callback.
                connectionIdleTimeout = executor().schedule(this, connectionIdleTimeNanos,
                                                            TimeUnit.NANOSECONDS);
                try {
                    if (!hasRequestsInProgress(ctx)) {
                        pingState = PingState.SHUTDOWN;
                        logger.debug("{} Closing an idle {} connection", ctx.channel(), name);
                        ctx.channel().close();
                    }
                } catch (Exception e) {
                    if (!warn) {
                        logger.warn("An error occurred while notifying an all idle event", e);
                        warn = true;
                    }
                }
            } else {
                // Either read or write occurred before the connection idle timeout - set a new
                // timeout with shorter delay.
                connectionIdleTimeout = executor().schedule(this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class PingIdleTimeoutTask extends AbstractKeepAliveTask {

        private boolean warn;

        PingIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {

            final long lastPingIdleTime = AbstractKeepAliveHandler.this.lastPingIdleTime;
            final long nextDelay;
            nextDelay = pingIdleTimeNanos - (System.nanoTime() - lastPingIdleTime);
            if (nextDelay <= 0) {
                // PING is idle - set a new timeout and notify the callback.
                pingIdleTimeout = executor().schedule(this, pingIdleTimeNanos, TimeUnit.NANOSECONDS);

                final boolean isFirst = firstPingIdleEvent;
                firstPingIdleEvent = false;
                try {
                    if (pingIdleTimeNanos > 0 && isFirst) {
                        pingState = PingState.PING_SCHEDULED;
                        writePing(ctx).addListener(pingWriteListener);
                    }
                } catch (Exception e) {
                    if (!warn) {
                        logger.warn("An error occurred while notifying a ping idle event", e);
                        warn = true;
                    }
                }
            } else {
                // A PING was sent or received within the ping timeout
                // - set a new timeout with shorter delay.
                pingIdleTimeout = executor().schedule(this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class MaxConnectionAgeExceededTask extends AbstractKeepAliveTask {

        MaxConnectionAgeExceededTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            try {
                isMaxConnectionAgeExceeded = true;

                // A connection exceeding the max age will be closed with:
                // - HTTP/2 server: Sending GOAWAY frame after writing headers
                // - HTTP/1 server: Sending 'Connection: close' header when writing headers
                // - HTTP/2 client
                //   - Sending GOAWAY frame after receiving the end of a stream
                //   - Or closed by this task if the connection is idle
                // - HTTP/1 client
                //   - Close the connection after fully receiving a response
                //   - Or closed by this task if the connection is idle

                if (!isServer && !hasRequestsInProgress(ctx)) {
                    logger.debug("{} Closing a {} connection exceeding the max age: {}ns",
                                 ctx.channel(), name, maxConnectionAgeNanos);
                    ctx.channel().close();
                }
            } catch (Exception e) {
                logger.warn("Unexpected error occurred while closing a connection exceeding the max age", e);
            }
        }
    }

    enum KeepAliveType {
        HTTP2_SERVER,
        HTTP1_SERVER,
        HTTP2_CLIENT,
        HTTP1_CLIENT
    }
}
