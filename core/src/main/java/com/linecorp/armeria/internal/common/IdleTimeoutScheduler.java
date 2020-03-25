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

import static com.linecorp.armeria.internal.common.IdleStateEvent.ALL_IDLE_STATE_EVENT;
import static com.linecorp.armeria.internal.common.IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT;
import static com.linecorp.armeria.internal.common.IdleStateEvent.FIRST_PING_IDLE_STATE_EVENT;
import static com.linecorp.armeria.internal.common.IdleStateEvent.PING_IDLE_STATE_EVENT;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.ObjectUtil;

/**
 * A Scheduler that triggers an {@link IdleStateEvent} when {@link #onReadOrWrite()} or {@linkplain #onPing()}
 * has not invoked for a while.
 *
 * <h3>Supported idle states</h3>
 * <table border="1">
 * <tr><th>Property</th><th>Meaning</th></tr>
 * <tr>
 *   <td>{@code allIdleTime}</td>
 *   <td>an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
 *       will be triggered when neither read nor write was performed for the
 *       specified period of time. Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 *   <td>{@code pingIdleTime}</td>
 *   <td>an {@link IdleStateEvent} whose state is {@link IdleState#PING_IDLE}
 *       will be triggered when neither read nor write was performed for the
 *       specified period of time. Specify {@code 0} to disable.</td>
 * </tr>
 * </table>
 */
abstract class IdleTimeoutScheduler {

    // Forked from Netty 4.1.48
    // https://github.com/netty/netty/blob/81513c3728df8add3c94fd0bdaaf9ba424925b29/handler/src/main/java/io/netty/handler/timeout/IdleStateHandler.java#L99

    private static final Logger logger = LoggerFactory.getLogger(IdleTimeoutScheduler.class);

    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private static long ticksInNanos() {
        return System.nanoTime();
    }

    private static IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        switch (state) {
            case ALL_IDLE:
                return first ? FIRST_ALL_IDLE_STATE_EVENT : ALL_IDLE_STATE_EVENT;
            case PING_IDLE:
                return first ? FIRST_PING_IDLE_STATE_EVENT : PING_IDLE_STATE_EVENT;
            default:
                throw new IllegalArgumentException("Unhandled: state=" + state + ", first=" + first);
        }
    }

    private final long allIdleTimeNanos;
    private final long pingIdleTimeNanos;
    private final ScheduledExecutorService executor;

    @Nullable
    private ScheduledFuture<?> allIdleTimeout;
    private long lastAllIdleTime;
    private boolean firstAllIdleEvent = true;

    @Nullable
    private ScheduledFuture<?> pingIdleTimeout;
    private long lastPingAckTime;
    private boolean firstPingIdleEvent = true;

    private byte state; // 0 - none, 1 - initialized, 2 - destroyed

    /**
     * Creates a new instance schedules {@link IdleStateEvent}s.
     *
     * @param allIdleTime an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *                    will be triggered when neither read nor write was performed for
     *                    the specified period of time. Specify {@code 0} to disable.
     * @param pingIdleTime an {@link IdleStateEvent} whose state is {@link IdleState#PING_IDLE}
     *                     will be triggered when neither read nor write was performed for
     *                     the specified period of time. Specify {@code 0} to disable.
     * @param unit the {@link TimeUnit}
     * @param executor the executor to schedule a timeout to trigger an {@link IdleStateEvent}.
     */
    IdleTimeoutScheduler(long allIdleTime, long pingIdleTime, TimeUnit unit,
                         ScheduledExecutorService executor) {
        ObjectUtil.checkNotNull(unit, "unit");
        this.executor = executor;

        if (allIdleTime <= 0) {
            allIdleTimeNanos = 0;
        } else {
            allIdleTimeNanos = Math.max(unit.toNanos(allIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (pingIdleTime <= 0) {
            pingIdleTimeNanos = 0;
        } else {
            pingIdleTimeNanos = Math.max(unit.toNanos(pingIdleTime), MIN_TIMEOUT_NANOS);
        }
    }

    public void onReadOrWrite() {
        if (allIdleTimeNanos > 0 || pingIdleTimeNanos > 0) {
            lastAllIdleTime = lastPingAckTime = ticksInNanos();
            firstAllIdleEvent = firstPingIdleEvent = true;
        }
    }

    public void onPing() {
        if (pingIdleTimeNanos > 0) {
            firstPingIdleEvent = true;
            lastPingAckTime = ticksInNanos();
        }
    }

    public void initialize(ChannelHandlerContext ctx) {
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        switch (state) {
            case 1:
            case 2:
                return;
        }

        state = 1;

        if (allIdleTimeNanos > 0) {
            allIdleTimeout = executor.schedule(new AllIdleTimeoutTask(ctx),
                                               allIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (pingIdleTimeNanos > 0) {
            pingIdleTimeout = executor.schedule(new PingIdleTimeoutTask(ctx),
                                                pingIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void destroy() {
        state = 2;
        if (allIdleTimeout != null) {
            allIdleTimeout.cancel(false);
            allIdleTimeout = null;
        }
        if (pingIdleTimeout != null) {
            pingIdleTimeout.cancel(false);
            pingIdleTimeout = null;
        }
    }

    protected abstract void onIdleEvent(ChannelHandlerContext ctx, IdleStateEvent evt);

    private abstract static class AbstractIdleTask implements Runnable {

        private final ChannelHandlerContext ctx;

        AbstractIdleTask(ChannelHandlerContext ctx) {
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

    private final class AllIdleTimeoutTask extends AbstractIdleTask {

        private boolean warn;

        AllIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {

            final long lastAllIdleTime = IdleTimeoutScheduler.this.lastAllIdleTime;
            final long nextDelay = allIdleTimeNanos - (ticksInNanos() - lastAllIdleTime);
            if (nextDelay <= 0) {
                // Both reader and writer are idle - set a new timeout and
                // notify the callback.
                allIdleTimeout = executor.schedule(this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

                final boolean first = firstAllIdleEvent;
                firstAllIdleEvent = false;
                final IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                try {
                    onIdleEvent(ctx, event);
                } catch (Exception e) {
                    if (!warn) {
                        logger.warn("An error occurred while notifying an all idle event", e);
                        warn = true;
                    }
                }
            } else {
                // Either read or write occurred before the timeout - set a new
                // timeout with shorter delay.
                allIdleTimeout = executor.schedule(this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class PingIdleTimeoutTask extends AbstractIdleTask {

        private boolean warn;

        PingIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {

            final long lastPingAckTime = IdleTimeoutScheduler.this.lastPingAckTime;
            final long nextDelay = pingIdleTimeNanos - (ticksInNanos() - lastPingAckTime);
            if (nextDelay <= 0) {
                // Ping is idle - set a new timeout and notify the callback.
                pingIdleTimeout = executor.schedule(this, pingIdleTimeNanos, TimeUnit.NANOSECONDS);

                final boolean first = firstPingIdleEvent;
                firstPingIdleEvent = false;

                final IdleStateEvent event = newIdleStateEvent(IdleState.PING_IDLE, first);
                try {
                    onIdleEvent(ctx, event);
                } catch (Exception e) {
                    if (!warn) {
                        logger.warn("An error occurred while notifying a ping idle event", e);
                        warn = true;
                    }
                }
            } else {
                // Ping occurred before the timeout - set a new timeout with shorter delay.
                pingIdleTimeout = executor.schedule(this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }
}
