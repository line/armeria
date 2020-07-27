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
package com.linecorp.armeria.internal.common;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

/**
 * A controller that schedules the timeout task with the initial value or reschedule when the timeout
 * setting is changed.
 */
public interface TimeoutController {

    /**
     * Schedules a new timeout with the specified {@code timeoutNanos}.
     * If a timeout is scheduled already, this method will not start a new timeout.
     *
     * @param timeoutNanos a positive time amount value in nanoseconds.
     * @return {@code true} if the timeout is scheduled.
     *         {@code false} if the timeout has been scheduled, triggered already
     *         or a timeout cannot be scheduled, e.g. request or response has been handled already.
     */
    boolean scheduleTimeoutNanos(long timeoutNanos);

    /**
     * Extends the current timeout by the specified {@code adjustmentNanos}.
     * Note that a negative {@code adjustmentNanos} reduces the current timeout.
     *
     * @param adjustmentNanos the adjustment of time amount value in nanoseconds.
     * @return {@code true} if the current timeout is extended by the specified {@code adjustmentNanos}.
     *         {@code false} if no timeout was scheduled previously, the timeout has been triggered already
     *         or a timeout cannot be scheduled, e.g. request or response has been handled already.
     */
    boolean extendTimeoutNanos(long adjustmentNanos);

    /**
     * Sets the amount of time that is after the specified {@code newTimeoutNanos} from now.
     *
     * @param newTimeoutNanos the new timeout value in nanoseconds. {@code 0} if disabled.
     * @return {@code true} if the current timeout is reset by the specified {@code newTimeoutNanos}.
     *         {@code false} if the timeout has been triggered already
     *         or a timeout cannot be scheduled, e.g. request or response has been handled already.
     */
    boolean resetTimeoutNanos(long newTimeoutNanos);

    /**
     * Trigger the current timeout immediately.
     *
     * @return {@code true} if the current timeout is triggered successfully.
     *         {@code false} if the timeout has been triggered already
     *         or a timeout cannot be scheduled, e.g. request or response has been handled already.
     */
    boolean timeoutNow();

    /**
     * Cancels the current timeout scheduled. You can schedule a new timeout with
     * {@link #scheduleTimeoutNanos(long)} if the current timeout is cancelled successfully.
     * @return {@code true} if the current timeout is cancelled.
     *         {@code false} if the timeout has been triggered already or no timeout was scheduled previously.
     */
    boolean cancelTimeout();

    /**
     * Returns whether the timeout has been triggered or not.
     *
     * @return {@code true} if the timeout has been triggered already.
     *         {@code false} if the timeout is scheduled now or no timeout was scheduled previously.
     */
    boolean isTimedOut();

    /**
     * Returns a {@link CompletableFuture} that completes when the current timeout is triggering.
     */
    CompletableFuture<Void> whenTimingOut();

    /**
     * Returns a {@link CompletableFuture} that completes when the current timeout is triggered successfully.
     */
    CompletableFuture<Void> whenTimedOut();

    /**
     * Returns the start time of the initial timeout in nanoseconds
     * or {@code null} if no timeout was scheduled previously.
     */
    @Nullable
    Long startTimeNanos();
}
