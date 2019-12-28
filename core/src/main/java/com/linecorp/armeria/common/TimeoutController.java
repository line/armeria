/*
 * Copyright 2019 LINE Corporation
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

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import io.netty.channel.EventLoop;

/**
 * A controller that is set to a deadline or resets to a timeout when the timeout setting is changed.
 *
 * <p>Note: This interface is meant for internal use to schedule a initial timeout task or
 * reschedule a timeout task when a user updates the timeout configuration.
 */
public abstract class TimeoutController {

    private long timeoutMillis;
    private long firstStartTimeNanos;
    private long lastStartTimeNanos;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;

    protected TimeoutController() {}

    protected TimeoutController(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Initialize the timeout scheduler with the {@code timeoutMillis} initialized by the constructor.
     */
    public final void initTimeout() {
        initTimeout(timeoutMillis);
    }

    /**
     * Initialize the timeout scheduler with the specified {@code timeoutMillis}.
     */
    public final void initTimeout(long timeoutMillis) {
        if (timeoutFuture != null || timeoutMillis <= 0 || !isReady()) {
            // No need to schedule a response timeout if:
            // - the timeout has been scheduled already,
            // - the timeout has been disabled or
            // - the status is not ready yet.
            return;
        }
        this.timeoutMillis = timeoutMillis;
        firstStartTimeNanos = lastStartTimeNanos = System.nanoTime();
        timeoutFuture = eventLoop().schedule(this::onTimeout, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Adjusts the current timeout by the specified {@code adjustmentMillis}.
     *
     * @param adjustmentMillis the adjustment of time amount value in milliseconds.
     */
    public final void adjustTimeout(long adjustmentMillis) {
        if (adjustmentMillis == 0) {
            return;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();

        if (!isDone()) {
            // Calculate the amount of time passed since the creation of this subscriber.
            final long currentNanoTime = System.nanoTime();
            final long passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentNanoTime - lastStartTimeNanos);
            final long newTimeoutMillis = timeoutMillis + adjustmentMillis - passedTimeMillis;
            timeoutMillis = newTimeoutMillis;
            lastStartTimeNanos = currentNanoTime;
            if (newTimeoutMillis > 0) {
                timeoutFuture = eventLoop().schedule(
                        this::onTimeout, newTimeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                // We went past the dead line set by the new timeout already.
                onTimeout();
            }
        }
    }

    /**
     * Sets the new timeout that is after the specified {@code newTimeoutMillis} from the now.
     *
     * @param newTimeoutMillis the new timeout value in milliseconds. {@code 0} if disabled.
     */
    public final void resetTimeout(long newTimeoutMillis) {
        if (newTimeoutMillis <= 0) {
            cancelTimeout();
            return;
        }

        if (!isDone()) {
            final long currentNanoTime = System.nanoTime();
            final long passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentNanoTime - lastStartTimeNanos);
            final long remainingTimeoutMillis = timeoutMillis - passedTimeMillis;
            lastStartTimeNanos = currentNanoTime;
            if (remainingTimeoutMillis == newTimeoutMillis) {
                return;
            }

            // Cancel the previously scheduled timeout, if exists.
            cancelTimeout();
            timeoutMillis = newTimeoutMillis;
            timeoutFuture = eventLoop().schedule(this::onTimeout, newTimeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Returns the start time of the initial timeout in nanoseconds.
     */
    public final long startTimeNanos() {
        return firstStartTimeNanos;
    }

    protected abstract EventLoop eventLoop();

    protected abstract boolean isReady();

    protected abstract boolean isDone();

    protected abstract void onTimeout();

    protected final boolean cancelTimeout() {
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture == null) {
            return true;
        }

        this.timeoutFuture = null;
        return timeoutFuture.cancel(false);
    }
}
