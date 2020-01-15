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
package com.linecorp.armeria.common.util;

/**
 * A controller that schedules the timeout task with the initial value or reschedule when the timeout
 * setting is changed.
 *
 * <p>Note: This interface is meant for internal use only.
 */
public interface TimeoutController {

    /**
     * Schedules a new timeout with the specified {@code timeoutMillis}.
     * If a timeout is scheduled already, this method will not start a new timeout.
     * @return {@code true} if the timeout is scheduled.
     *         {@code false} if the timeout could not be scheduled because the timeout is currently being
     *         scheduled.
     */
    boolean scheduleTimeout(long timeoutMillis);

    /**
     * Extends the current timeout by the specified {@code adjustmentMillis}.
     * This method does nothing if no timeout was scheduled previously.
     * Note that a negative {@code adjustmentMillis} reduces the current timeout.
     *
     * @param adjustmentMillis the adjustment of time amount value in milliseconds.
     * @return {@code true} if the current timeout is extended by the specified {@code adjustmentMillis}.
     *         {@code false} if the current timeout could not be extended
     *         because of some constraints in the class that implements this interface.
     */
    boolean extendTimeout(long adjustmentMillis);

    /**
     * Sets the amount of time that is after the specified {@code newTimeoutMillis} from now.
     *
     * @param newTimeoutMillis the new timeout value in milliseconds. {@code 0} if disabled.
     * @return {@code true} if the current timeout is reset by the specified {@code newTimeoutMillis}.
     *         {@code false} if the current timeout could not be reset
     *         because of some constraints in the class that implements this interface.
     */
    boolean resetTimeout(long newTimeoutMillis);

    /**
     * Trigger the current timeout immediately.
     * @return {@code true} if the current timeout is triggered successfully.
     *         {@code false} if the current timeout could not be triggered now because
     *         the timeout schedule has already completed normally.
     */
    boolean timeoutNow();

    /**
     * Cancels the current timeout scheduled.
     * @return {@code true} if the current timeout is canceled or no timeout is being scheduled.
     *         {@code false} if the current timeout could not be cancelled typically because
     *         the timeout schedule has already completed normally.
     */
    boolean cancelTimeout();

    /**
     * Returns the start time of the initial timeout in nanoseconds.
     */
    long startTimeNanos();
}
