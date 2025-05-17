/*
 * Copyright 2025 LINE Corporation
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
 *
 */

package com.linecorp.armeria.common.stream;

/**A strategy that determines whether a {@link StreamMessage} has timed out
 * and, if not, when to schedule the next timeout check as needed.
 *
 * <p>This strategy defines two methods:</p>
 * <ul>
 *   <li>{@link #initialDecision(long)} – invoked once immediately after subscription,
 *       using the subscription time to return the first {@link StreamTimeoutDecision}.</li>
 *   <li>{@link #evaluateTimeout(long, long)} – invoked when the scheduled timer fires
 *       to re-evaluate the timeout status and, if necessary, schedule the next check.</li>
 * </ul>
 *
 * <p>All timestamps are in the same monotonic time domain as
 * {@link System#nanoTime()}.</p>
 */
public interface StreamTimeoutStrategy {

    /**
     * Invoked once immediately after subscription to determine the next timeout
     * evaluation time based on the subscription timestamp.
     *
     * @param subscribeTimeNanos the {@code System.nanoTime()} value captured at the moment of subscription
     * @return a {@link StreamTimeoutDecision} representing the next timeout evaluation time
     */
    StreamTimeoutDecision initialDecision(long subscribeTimeNanos);

    /**
     * Invoked when the scheduled timer fires to re-evaluate the timeout status
     * and, if necessary, schedule the next check.
     *
     * @param currentTimeNanos   the {@code System.nanoTime()} value when the scheduled timer fires
     * @param lastEventTimeNanos the {@code System.nanoTime()} value of the last received data event
     * @return a {@link StreamTimeoutDecision} containing the timeout result and the next evaluation time
     */
    StreamTimeoutDecision evaluateTimeout(long currentTimeNanos, long lastEventTimeNanos);
}