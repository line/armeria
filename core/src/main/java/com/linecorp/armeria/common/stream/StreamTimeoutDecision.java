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

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Result object returned by both {@link StreamTimeoutStrategy#initialDecision()}
 * and
 * {@link StreamTimeoutStrategy#evaluateTimeout(long, long)}.
 *
 * <p>A decision has exactly <strong>three</strong> possible meanings:</p>
 * <ul>
 *   <li>{@link #TIMED_OUT} – the stream is already timed-out.</li>
 *   <li>{@link #NO_SCHEDULE} – keep the stream alive with <em>no</em> further timeout checks.</li>
 *   <li>{@link #scheduleAfter(long)} – keep the stream alive and re-evaluate after the given
 *       <em>delay (nanoseconds)</em>.</li>
 * </ul>
 */
public final class StreamTimeoutDecision {

    /**
     * Indicates that the stream has timed out and must be closed.
     */
    public static final StreamTimeoutDecision TIMED_OUT =
            new StreamTimeoutDecision(true, 0);

    /**
     * Indicates that no further timeout checks are necessary.
     */
    public static final StreamTimeoutDecision NO_SCHEDULE =
            new StreamTimeoutDecision(false, 0);

    /**
     * Creates a decision instructing the caller to run the next timeout check after
     * the specified <em>positive</em> delay.
     *
     * @param nextDelayNanos delay (in nanoseconds) before the next evaluation; must be {@code > 0}
     * @return a new {@link StreamTimeoutDecision}
     *
     * @throws IllegalArgumentException if {@code nextDelayNanos&nbsp;&le;&nbsp;0}
     */
    public static StreamTimeoutDecision scheduleAfter(long nextDelayNanos) {
        checkArgument(nextDelayNanos > 0,
                      "delay must be positive: %s", nextDelayNanos);
        return new StreamTimeoutDecision(false, nextDelayNanos);
    }

    private final boolean timedOut;

    /**
     * Delay until the next check (ns); {@code 0} ⇒ no schedule.
     */
    private final long nextDelayNanos;

    /**
     * Creates a new {@link StreamTimeoutDecision}.
     *
     * @param timedOut whether the stream should be closed immediately
     * @param nextDelayNanos delay in nanoseconds until the next evaluation (0 means no further check)
     */
    private StreamTimeoutDecision(boolean timedOut, long nextDelayNanos) {
        this.timedOut = timedOut;
        this.nextDelayNanos = nextDelayNanos;
    }

    /**
     * Returns whether the stream should be closed immediately due to a timeout.
     *
     * @return {@code true} if the stream must be closed immediately
     */
    boolean timedOut() {
        return timedOut;
    }

    /**
     * Returns the delay in nanoseconds until the next timeout evaluation.
     *
     * @return a positive value when another check is required,
     *         or {@code 0} when no further scheduling is needed
     *         (i.e.&nbsp;this decision is {@link #NO_SCHEDULE} or {@link #TIMED_OUT})
     */
    long nextDelayNanos() {
        return nextDelayNanos;
    }

    @Override
    public String toString() {
        if (timedOut) {
            return "TIMED_OUT";
        }
        return (nextDelayNanos == 0) ? "NO_SCHEDULE"
               : "scheduleAfter(" + nextDelayNanos + "ns)";
    }
}
