/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.client.retry.FixedBackoff.NO_DELAY;

/**
 * Control back off between attempts in a single retry operation.
 */
@FunctionalInterface
public interface Backoff {
    /**
     * Returns a {@link Backoff} that that will never wait between attempts.
     */
    static Backoff withoutDelay() {
        return NO_DELAY;
    }

    /**
     * Returns a {@link Backoff} that waits a fixed interval between attempts.
     */
    static Backoff fixed(long intervalMillis) {
        return new FixedBackoff(intervalMillis);
    }

    /**
     * Returns a {@link Backoff} that provides an interval (that increases exponentially) between two attempts.
     */
    static Backoff exponential(long minIntervalMillis, long maxIntervalMillis) {
        return exponential(minIntervalMillis, maxIntervalMillis, 2.0f);
    }

    /**
     * Returns a {@link Backoff} that waits an exponentially-increasing amount of time between attempts.
     */
    static Backoff exponential(long minIntervalMillis, long maxIntervalMillis, float multiplier) {
        return new ExponentialBackoff(minIntervalMillis, maxIntervalMillis, multiplier);
    }

    /**
     * Returns the amount of time to wait before attempting a retry, in milliseconds.
     * @param numAttemptsSoFar the number of attempts made by a client so far, including the first attempt and
     *                         its following retries.
     */
    long nextIntervalMillis(int numAttemptsSoFar);

    /**
     * Returns a {@link Backoff} that provides an interval that increases using
     * <a href="https://www.awsarchitectureblog.com/2015/03/backoff.html">full jitter</a> strategy.
     */
    default Backoff withJitter(long minJitterMillis, long maxJitterMillis) {
        return new JitterAddingBackoff(this, minJitterMillis, maxJitterMillis);
    }

    /**
     * Returns a {@link Backoff} which limits the number of attempts up to the specified value.
     */
    default Backoff withMaxAttempts(int maxAttempts) {
        return new AttemptLimitingBackoff(this, maxAttempts);
    }
}
