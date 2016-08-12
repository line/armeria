/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

/**
 * An immutable object that stores the count of events.
 */
public final class EventCount {

    /**
     * An {@link EventCount} without any successes and failures.
     */
    public static final EventCount ZERO = new EventCount(0, 0);

    private final long success;

    private final long failure;

    /**
     * Creates a new instance with the specified number of successes and failures.
     */
    public EventCount(long success, long failure) {
        this.success = success;
        this.failure = failure;
        assert 0 <= success;
        assert 0 <= failure;
    }

    /**
     * Returns the number of success events.
     */
    public long success() {
        return success;
    }

    /**
     * Returns the number of failure events.
     */
    public long failure() {
        return failure;
    }

    /**
     * Returns the total number of events.
     */
    public long total() {
        return success + failure;
    }

    /**
     * Returns the success rate (success/total), or throws an {@link ArithmeticException} if total is 0.
     */
    public double successRate() {
        final long total = total();
        if (total == 0) {
            throw new ArithmeticException("Failed to calculate success rate since total count is 0");
        }
        return success / (double) total;
    }

    /**
     * Returns the failure rate (failure/total), or throws an {@link ArithmeticException} if total is 0.
     */
    public double failureRate() {
        final long total = total();
        if (total == 0) {
            throw new ArithmeticException("Failed to calculate failure rate since total count is 0");
        }
        return failure / (double) total;
    }

    @Override
    public String toString() {
        final long total = total();
        if (total == 0) {
            return "success% = NaN (0/0)";
        }
        final double percentageOfSuccess = 100 * successRate();
        return String.format("success%% = %.2f%% (%d/%d)", percentageOfSuccess, success(), total);
    }

    @Override
    public int hashCode() {
        return (int) (31 * success + failure);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventCount)) {
            return false;
        }
        EventCount that = (EventCount) o;
        return success == that.success && failure == that.failure;
    }
}
