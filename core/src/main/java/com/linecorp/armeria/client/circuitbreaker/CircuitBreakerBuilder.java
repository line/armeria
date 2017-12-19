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
import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;

/**
 * Builds a {@link CircuitBreaker} instance using builder pattern.
 */
public final class CircuitBreakerBuilder {

    private static final class Defaults {

        private static final double FAILURE_RATE_THRESHOLD = 0.8;

        private static final ExceptionFilter EXCEPTION_FILTER = cause -> true;

        private static final long MINIMUM_REQUEST_THRESHOLD = 10;

        private static final Duration TRIAL_REQUEST_INTERVAL = Duration.ofSeconds(3);

        private static final Duration CIRCUIT_OPEN_WINDOW = Duration.ofSeconds(10);

        private static final Duration COUNTER_SLIDING_WINDOW = Duration.ofSeconds(20);

        private static final Duration COUNTER_UPDATE_INTERVAL = Duration.ofSeconds(1);

        private static final Ticker TICKER = Ticker.systemTicker();
    }

    private final Optional<String> name;

    private double failureRateThreshold = Defaults.FAILURE_RATE_THRESHOLD;

    private ExceptionFilter exceptionFilter = Defaults.EXCEPTION_FILTER;

    private long minimumRequestThreshold = Defaults.MINIMUM_REQUEST_THRESHOLD;

    private Duration trialRequestInterval = Defaults.TRIAL_REQUEST_INTERVAL;

    private Duration circuitOpenWindow = Defaults.CIRCUIT_OPEN_WINDOW;

    private Duration counterSlidingWindow = Defaults.COUNTER_SLIDING_WINDOW;

    private Duration counterUpdateInterval = Defaults.COUNTER_UPDATE_INTERVAL;

    private Ticker ticker = Defaults.TICKER;

    private List<CircuitBreakerListener> listeners = Collections.emptyList();

    /**
     * Creates a new {@link CircuitBreakerBuilder} with the specified name.
     *
     * @param name The name of the circuit breaker.
     */
    public CircuitBreakerBuilder(String name) {
        requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name: <empty> (expected: a non-empty string)");
        }
        this.name = Optional.of(name);
    }

    /**
     * Creates a new {@link CircuitBreakerBuilder}.
     */
    public CircuitBreakerBuilder() {
        name = Optional.empty();
    }

    /**
     * Sets the threshold of failure rate to detect a remote service fault.
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
     * Sets the trial request interval in HALF_OPEN state.
     */
    public CircuitBreakerBuilder trialRequestIntervalMillis(long trialRequestIntervalMillis) {
        trialRequestInterval(Duration.ofMillis(trialRequestIntervalMillis));
        return this;
    }

    /**
     * Sets the duration of OPEN state.
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
     * Sets the duration of OPEN state.
     */
    public CircuitBreakerBuilder circuitOpenWindowMillis(long circuitOpenWindowMillis) {
        circuitOpenWindow(Duration.ofMillis(circuitOpenWindowMillis));
        return this;
    }

    /**
     * Sets the time length of sliding window to accumulate the count of events.
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
     * Sets the time length of sliding window to accumulate the count of events.
     */
    public CircuitBreakerBuilder counterSlidingWindowMillis(long counterSlidingWindowMillis) {
        counterSlidingWindow(Duration.ofMillis(counterSlidingWindowMillis));
        return this;
    }

    /**
     * Sets the interval that a circuit breaker can see the latest accumulated count of events.
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
     * Sets the interval that a circuit breaker can see the latest accumulated count of events.
     */
    public CircuitBreakerBuilder counterUpdateIntervalMillis(long counterUpdateIntervalMillis) {
        counterUpdateInterval(Duration.ofMillis(counterUpdateIntervalMillis));
        return this;
    }

    /**
     * Sets the {@link ExceptionFilter} that decides whether the circuit breaker should deal with a given error.
     */
    public CircuitBreakerBuilder exceptionFilter(ExceptionFilter exceptionFilter) {
        this.exceptionFilter = requireNonNull(exceptionFilter, "exceptionFilter");
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
                                         exceptionFilter, Collections.unmodifiableList(listeners)));
    }
}
