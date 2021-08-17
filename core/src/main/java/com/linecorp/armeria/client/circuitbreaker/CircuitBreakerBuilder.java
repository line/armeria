/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Ticker;

/**
 * Builds a {@link CircuitBreaker} instance using builder pattern.
 */
public final class CircuitBreakerBuilder {

    private static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.5;
    private static final long DEFAULT_MINIMUM_REQUEST_THRESHOLD = 10;
    private static final int DEFAULT_TRIAL_REQUEST_INTERVAL_SECONDS = 3;
    private static final int DEFAULT_CIRCUIT_OPEN_WINDOW_SECONDS = 10;
    private static final int DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS = 20;
    private static final int DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS = 1;
    private static final Ticker DEFAULT_TICKER = Ticker.systemTicker();

    @Nullable
    private final String name;

    private double failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;

    private long minimumRequestThreshold = DEFAULT_MINIMUM_REQUEST_THRESHOLD;

    private Duration trialRequestInterval = Duration.ofSeconds(DEFAULT_TRIAL_REQUEST_INTERVAL_SECONDS);

    private Duration circuitOpenWindow = Duration.ofSeconds(DEFAULT_CIRCUIT_OPEN_WINDOW_SECONDS);

    private Duration counterSlidingWindow = Duration.ofSeconds(DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS);

    private Duration counterUpdateInterval = Duration.ofSeconds(DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS);

    private Ticker ticker = DEFAULT_TICKER;

    private List<CircuitBreakerListener> listeners = Collections.emptyList();

    CircuitBreakerBuilder(String name) {
        requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name: <empty> (expected: a non-empty string)");
        }
        this.name = name;
    }

    CircuitBreakerBuilder() {
        name = null;
    }

    /**
     * Sets the threshold of failure rate to detect a remote service fault.
     * Defaults to {@value #DEFAULT_FAILURE_RATE_THRESHOLD} if unspecified.
     *
     * @param failureRateThreshold The rate between 0 (exclusive) and 1 (inclusive)
     */
    public CircuitBreakerBuilder failureRateThreshold(double failureRateThreshold) {
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
    public CircuitBreakerBuilder minimumRequestThreshold(long minimumRequestThreshold) {
        if (minimumRequestThreshold < 0) {
            throw new IllegalArgumentException(
                    "minimumRequestThreshold: " + minimumRequestThreshold + " (expected: >= 0)");
        }
        this.minimumRequestThreshold = minimumRequestThreshold;
        return this;
    }

    /**
     * Sets the trial request interval in HALF_OPEN state.
     * Defaults to {@value #DEFAULT_TRIAL_REQUEST_INTERVAL_SECONDS} seconds if unspecified.
     */
    public CircuitBreakerBuilder trialRequestInterval(Duration trialRequestInterval) {
        requireNonNull(trialRequestInterval, "trialRequestInterval");
        if (trialRequestInterval.isNegative() || trialRequestInterval.isZero()) {
            throw new IllegalArgumentException(
                    "trialRequestInterval: " + trialRequestInterval + " (expected: > 0)");
        }
        this.trialRequestInterval = trialRequestInterval;
        return this;
    }

    /**
     * Sets the trial request interval in HALF_OPEN state in milliseconds.
     * Defaults to {@value #DEFAULT_TRIAL_REQUEST_INTERVAL_SECONDS} seconds if unspecified.
     */
    public CircuitBreakerBuilder trialRequestIntervalMillis(long trialRequestIntervalMillis) {
        trialRequestInterval(Duration.ofMillis(trialRequestIntervalMillis));
        return this;
    }

    /**
     * Sets the duration of OPEN state.
     * Defaults to {@value #DEFAULT_CIRCUIT_OPEN_WINDOW_SECONDS} seconds if unspecified.
     */
    public CircuitBreakerBuilder circuitOpenWindow(Duration circuitOpenWindow) {
        requireNonNull(circuitOpenWindow, "circuitOpenWindow");
        if (circuitOpenWindow.isNegative() || circuitOpenWindow.isZero()) {
            throw new IllegalArgumentException(
                    "circuitOpenWindow: " + circuitOpenWindow + " (expected: > 0)");
        }
        this.circuitOpenWindow = circuitOpenWindow;
        return this;
    }

    /**
     * Sets the duration of OPEN state in milliseconds.
     * Defaults to {@value #DEFAULT_CIRCUIT_OPEN_WINDOW_SECONDS} seconds if unspecified.
     */
    public CircuitBreakerBuilder circuitOpenWindowMillis(long circuitOpenWindowMillis) {
        circuitOpenWindow(Duration.ofMillis(circuitOpenWindowMillis));
        return this;
    }

    /**
     * Sets the time length of sliding window to accumulate the count of events.
     * Defaults to {@value #DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS} seconds if unspecified.
     */
    public CircuitBreakerBuilder counterSlidingWindow(Duration counterSlidingWindow) {
        requireNonNull(counterSlidingWindow, "counterSlidingWindow");
        if (counterSlidingWindow.isNegative() || counterSlidingWindow.isZero()) {
            throw new IllegalArgumentException(
                    "counterSlidingWindow: " + counterSlidingWindow + " (expected: > 0)");
        }
        this.counterSlidingWindow = counterSlidingWindow;
        return this;
    }

    /**
     * Sets the time length of sliding window to accumulate the count of events, in milliseconds.
     * Defaults to {@value #DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS} seconds if unspecified.
     */
    public CircuitBreakerBuilder counterSlidingWindowMillis(long counterSlidingWindowMillis) {
        counterSlidingWindow(Duration.ofMillis(counterSlidingWindowMillis));
        return this;
    }

    /**
     * Sets the interval that a circuit breaker can see the latest accumulated count of events.
     * Defaults to {@value #DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS} second if unspecified.
     */
    public CircuitBreakerBuilder counterUpdateInterval(Duration counterUpdateInterval) {
        requireNonNull(counterUpdateInterval, "counterUpdateInterval");
        if (counterUpdateInterval.isNegative() || counterUpdateInterval.isZero()) {
            throw new IllegalArgumentException(
                    "counterUpdateInterval: " + counterUpdateInterval + " (expected: > 0)");
        }
        this.counterUpdateInterval = counterUpdateInterval;
        return this;
    }

    /**
     * Sets the interval that a circuit breaker can see the latest accumulated count of events, in milliseconds.
     * Defaults to {@value #DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS} second if unspecified.
     */
    public CircuitBreakerBuilder counterUpdateIntervalMillis(long counterUpdateIntervalMillis) {
        counterUpdateInterval(Duration.ofMillis(counterUpdateIntervalMillis));
        return this;
    }

    /**
     * Adds a {@link CircuitBreakerListener}.
     */
    public CircuitBreakerBuilder listener(CircuitBreakerListener listener) {
        requireNonNull(listener, "listener");
        if (listeners.isEmpty()) {
            listeners = new ArrayList<>(3);
        }
        listeners.add(listener);
        return this;
    }

    @VisibleForTesting
    CircuitBreakerBuilder ticker(Ticker ticker) {
        this.ticker = requireNonNull(ticker, "ticker");
        return this;
    }

    /**
     * Returns a newly-created {@link CircuitBreaker} based on the properties of this builder.
     */
    public CircuitBreaker build() {
        if (counterSlidingWindow.compareTo(counterUpdateInterval) <= 0) {
            throw new IllegalStateException(
                    "counterSlidingWindow: " + counterSlidingWindow + " (expected: > counterUpdateInterval)");
        }
        return new NonBlockingCircuitBreaker(
                ticker,
                new CircuitBreakerConfig(name, failureRateThreshold, minimumRequestThreshold,
                                         circuitOpenWindow, trialRequestInterval,
                                         counterSlidingWindow, counterUpdateInterval,
                                         Collections.unmodifiableList(listeners)));
    }
}
