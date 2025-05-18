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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;

/**
 * The result returned by {@link StreamTimeoutStrategy#evaluateTimeout(long, long)}.
 *
 * <p>A decision can be in one of three states:
 * <ul>
 *   <li>{@link #TIMED_OUT} – the stream has been considered timed-out.</li>
 *   <li>{@link #NO_SCHEDULE} – keep the stream alive with no further timeout checks.</li>
 *   <li>{@link #scheduleAt(long)} – keep the stream alive and re-evaluate at the given nano-time.</li>
 * </ul>
 *
 * <p>This class is immutable and thread-safe.</p>
 */
public final class StreamTimeoutDecision {

    /**
     * Sentinel value indicating that no more timeout evaluation is required.
     */
    private static final long NO_NEXT_SCHEDULE = Long.MIN_VALUE;

    /**
     * A shared instance that indicates the stream has already timed out.
     */
    public static final StreamTimeoutDecision TIMED_OUT =
            new StreamTimeoutDecision(true, NO_NEXT_SCHEDULE);

    /**
     * A shared instance that indicates the stream has not timed out
     * and no further timeout evaluation is necessary.
     */
    public static final StreamTimeoutDecision NO_SCHEDULE =
            new StreamTimeoutDecision(false, NO_NEXT_SCHEDULE);

    /**
     * Returns a decision that keeps the stream alive and schedules the next timeout
     * evaluation at the specified nano-time.
     *
     * @param nextScheduleTimeNanos the nano-time (as returned by {@link System#nanoTime()})
     *                              at which the next timeout check should run
     *
     * @throws IllegalArgumentException if {@code nextScheduleTimeNanos} is the reserved
     *                                  sentinel value {@link #NO_NEXT_SCHEDULE}
     */
    public static StreamTimeoutDecision scheduleAt(long nextScheduleTimeNanos) {
        checkArgument(nextScheduleTimeNanos != NO_NEXT_SCHEDULE,
                      "Reserved value: %s", nextScheduleTimeNanos);
        return new StreamTimeoutDecision(false, nextScheduleTimeNanos);
    }

    private final boolean timedOut;

    private final long nextScheduleTimeNanos;

    private StreamTimeoutDecision(boolean timedOut, long nextScheduleTimeNanos) {
        this.timedOut = timedOut;
        this.nextScheduleTimeNanos = nextScheduleTimeNanos;
    }

    /**
     * Returns whether the stream is considered timed-out.
     */
    public boolean timedOut() {
        return timedOut;
    }

    /**
     * Returns whether this decision contains a next schedule time.
     */
    public boolean hasNextSchedule() {
        return nextScheduleTimeNanos != NO_NEXT_SCHEDULE;
    }

    /**
     * Returns the nano-time at which the next timeout evaluation must occur.
     *
     * @return the scheduled nano-time
     *
     * @throws IllegalStateException if this decision has no next schedule
     *                               (i.e. {@link #hasNextSchedule()} is {@code false})
     */
    public long nextScheduleTimeNanos() {
        checkState(hasNextSchedule(), "nextScheduleTimeNanos not present");
        return nextScheduleTimeNanos;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("timedOut", timedOut)
                          .add("nextScheduleTimeNanos",
                               hasNextSchedule() ? nextScheduleTimeNanos : "N/A")
                          .toString();
    }
}
