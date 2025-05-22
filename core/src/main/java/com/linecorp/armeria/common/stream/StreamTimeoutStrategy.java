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

/**
 * A strategy that decides whether a {@link StreamMessage} has timed out and,
 * if not, how long to wait before the next timeout check.
 *
 * <p>Two entry points:</p>
 * <ul>
 *   <li>{@link #initialDecision()} – called once right after subscription.</li>
 *   <li>{@link #evaluateTimeout(long, long)} – called when the timer fires
 *       to re-evaluate and, if necessary, reschedule.</li>
 * </ul>
 *
 * <p>All timestamps use the same monotonic time source as
 * {@link System#nanoTime()}.</p>
 */
public interface StreamTimeoutStrategy {

    /**
     * Called immediately after subscription.
     *
     * @return the first {@link StreamTimeoutDecision}
     */
    StreamTimeoutDecision initialDecision();

    /**
     * Re-evaluates timeout status when the scheduled timer fires.
     *
     * @param currentTimeNanos   current {@code System.nanoTime()} value
     * @param lastEventTimeNanos {@code System.nanoTime()} of the last data event
     * @return a {@link StreamTimeoutDecision} describing whether the stream
     *         timed out and, if not, the delay before the next check
     */
    StreamTimeoutDecision evaluateTimeout(long currentTimeNanos, long lastEventTimeNanos);
}
