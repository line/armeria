/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.outlier;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Ticker;

/**
 * A builder for creating an {@link OutlierDetection}.
 */
@UnstableApi
public final class OutlierDetectionBuilder {

    private static final int DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS = 20;
    private static final int DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS = 1;
    private static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.5;
    private static final long DEFAULT_MINIMUM_REQUEST_THRESHOLD = 10;

    static final OutlierDetection DEFAULT_DETECTION =
            OutlierDetection.builder(OutlierRule.of())
                            .build();

    private final OutlierRule rule;

    private Ticker ticker = Ticker.systemTicker();
    private Duration counterSlidingWindow = Duration.ofSeconds(DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS);
    private Duration counterUpdateInterval = Duration.ofSeconds(DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS);
    private double failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
    private long minimumRequestThreshold = DEFAULT_MINIMUM_REQUEST_THRESHOLD;

    OutlierDetectionBuilder(OutlierRule rule) {
        requireNonNull(rule, "rule");
        this.rule = rule;
    }

    /**
     * Sets the {@link Ticker} to use for measuring elapsed time.
     */
    public OutlierDetectionBuilder ticker(Ticker ticker) {
        requireNonNull(ticker, "ticker");
        this.ticker = ticker;
        return this;
    }

    /**
     * Sets the time length of sliding window to accumulate the count of events.
     * Defaults to {@value #DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS} seconds if unspecified.
     */
    public OutlierDetectionBuilder counterSlidingWindow(Duration counterSlidingWindow) {
        requireNonNull(counterSlidingWindow, "counterSlidingWindow");
        checkArgument(!counterSlidingWindow.isNegative() && !counterSlidingWindow.isZero(),
                      "counterSlidingWindow: %s (expected: > 0)", counterSlidingWindow);
        this.counterSlidingWindow = counterSlidingWindow;
        return this;
    }

    /**
     * Sets the time length of sliding window to accumulate the count of events, in milliseconds.
     * Defaults to {@value #DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS} seconds if unspecified.
     */
    public OutlierDetectionBuilder counterSlidingWindowMillis(long counterSlidingWindowMillis) {
        return counterSlidingWindow(Duration.ofMillis(counterSlidingWindowMillis));
    }

    /**
     * Sets the interval that an {@link OutlierDetector} can see the latest accumulated count of events.
     * Defaults to {@value #DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS} second if unspecified.
     */
    public OutlierDetectionBuilder counterUpdateInterval(Duration counterUpdateInterval) {
        requireNonNull(counterUpdateInterval, "counterUpdateInterval");
        checkArgument(!counterUpdateInterval.isNegative() && !counterUpdateInterval.isZero(),
                      "counterUpdateInterval: %s (expected: > 0)", counterUpdateInterval);
        this.counterUpdateInterval = counterUpdateInterval;
        return this;
    }

    /**
     * Sets the interval that an {@link OutlierDetector} can see the latest accumulated count of events,
     * in milliseconds. Defaults to {@value #DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS} second if unspecified.
     */
    public OutlierDetectionBuilder counterUpdateIntervalMillis(long counterUpdateIntervalMillis) {
        return counterUpdateInterval(Duration.ofMillis(counterUpdateIntervalMillis));
    }

    /**
     * Sets the threshold of failure rate to detect a remote service fault.
     * Defaults to {@value #DEFAULT_FAILURE_RATE_THRESHOLD} if unspecified.
     *
     * @param failureRateThreshold The rate between 0 (exclusive) and 1 (inclusive)
     */
    public OutlierDetectionBuilder failureRateThreshold(double failureRateThreshold) {
        if (failureRateThreshold <= 0 || 1 < failureRateThreshold) {
            throw new IllegalArgumentException(
                    "failureRateThreshold: " + failureRateThreshold + " (expected: > 0 and <= 1)");
        }
        this.failureRateThreshold = failureRateThreshold;
        return this;
    }

    /**
     * Sets the minimum number of requests within a time window necessary to detect a remote service fault.
     * Defaults to {@value #DEFAULT_MINIMUM_REQUEST_THRESHOLD} if unspecified.
     */
    public OutlierDetectionBuilder minimumRequestThreshold(long minimumRequestThreshold) {
        if (minimumRequestThreshold < 0) {
            throw new IllegalArgumentException(
                    "minimumRequestThreshold: " + minimumRequestThreshold + " (expected: >= 0)");
        }
        this.minimumRequestThreshold = minimumRequestThreshold;
        return this;
    }

    /**
     * Returns a new {@link OutlierDetection} with the specified configuration.
     */
    public OutlierDetection build() {
        return new DefaultOutlierDetection(rule, ticker, counterSlidingWindow, counterUpdateInterval,
                                           failureRateThreshold, minimumRequestThreshold);
    }
}
