/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.client.circuitbreaker.CircuitBreakerConfig;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Builds an {@link OutlierDetectingEndpointGroup}.
 *
 * <p>See {@link OutlierDetectingEndpointGroup} for usage and the required HTTP decorator setup.
 */
@UnstableApi
public final class OutlierDetectingEndpointGroupBuilder {

    static final int DEFAULT_MAX_NUM_ENDPOINTS = 1024;
    /** Sentinel meaning the periodic age-based rotation is disabled by default. */
    static final long DEFAULT_MAX_ENDPOINT_AGE_MILLIS = -1;
    static final String DEFAULT_NAME_PREFIX = "outlier-detecting";

    /**
     * The default {@link CircuitBreakerRule} reports 5xx responses and exceptions as failures, and treats
     * everything else as a success.
     */
    private static final CircuitBreakerRule DEFAULT_RULE =
            CircuitBreakerRule.builder()
                              .onServerErrorStatus()
                              .onException()
                              .thenFailure();

    // CircuitBreaker defaults — kept in sync with CircuitBreakerBuilder.
    private static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.5;
    private static final long DEFAULT_MINIMUM_REQUEST_THRESHOLD = 10;
    private static final Duration DEFAULT_TRIAL_REQUEST_INTERVAL = Duration.ofSeconds(3);
    private static final Duration DEFAULT_CIRCUIT_OPEN_WINDOW = Duration.ofSeconds(10);
    private static final Duration DEFAULT_COUNTER_SLIDING_WINDOW = Duration.ofSeconds(20);
    private static final Duration DEFAULT_COUNTER_UPDATE_INTERVAL = Duration.ofSeconds(1);

    private final EndpointGroup delegate;

    private int maxNumEndpoints = DEFAULT_MAX_NUM_ENDPOINTS;
    private long maxEndpointAgeMillis = DEFAULT_MAX_ENDPOINT_AGE_MILLIS;

    // Outlier-specific (kept separate from CircuitBreakerConfig).
    private String namePrefix = DEFAULT_NAME_PREFIX;
    private boolean failFastOnAllCircuitOpen;
    private CircuitBreakerRule circuitBreakerRule = DEFAULT_RULE;

    // Per-endpoint CircuitBreaker settings.
    private double failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
    private long minimumRequestThreshold = DEFAULT_MINIMUM_REQUEST_THRESHOLD;
    private Duration trialRequestInterval = DEFAULT_TRIAL_REQUEST_INTERVAL;
    private Duration circuitOpenWindow = DEFAULT_CIRCUIT_OPEN_WINDOW;
    private Duration counterSlidingWindow = DEFAULT_COUNTER_SLIDING_WINDOW;
    private Duration counterUpdateInterval = DEFAULT_COUNTER_UPDATE_INTERVAL;

    @Nullable
    private MeterIdPrefix meterIdPrefix;
    @Nullable
    private MeterRegistry meterRegistry;

    OutlierDetectingEndpointGroupBuilder(EndpointGroup delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Sets the maximum number of endpoints kept in the rotation. Defaults to
     * {@value #DEFAULT_MAX_NUM_ENDPOINTS}, which is large enough for typical large-scale deployments
     * and avoids accidentally truncating the underlying group when this builder is used without
     * explicit configuration. Increase only when more concurrent endpoints are actually needed.
     */
    public OutlierDetectingEndpointGroupBuilder maxNumEndpoints(int maxNumEndpoints) {
        checkArgument(maxNumEndpoints > 0,
                      "maxNumEndpoints: %s (expected: > 0)", maxNumEndpoints);
        this.maxNumEndpoints = maxNumEndpoints;
        return this;
    }

    /**
     * Enables periodic age-based rotation by setting the lifespan of each endpoint. Once an endpoint
     * has been in the rotation for this duration, it is dropped and replaced by a fresh candidate from
     * the delegate (or reused from the keep-alive cache to preserve {@link CircuitBreaker} state).
     *
     * <p>Disabled by default — endpoints stay in the rotation indefinitely until they are evicted by
     * the per-endpoint {@link CircuitBreaker} or the delegate stops reporting them.
     */
    public OutlierDetectingEndpointGroupBuilder maxEndpointAge(Duration maxEndpointAge) {
        requireNonNull(maxEndpointAge, "maxEndpointAge");
        return maxEndpointAgeMillis(maxEndpointAge.toMillis());
    }

    /**
     * Enables periodic age-based rotation. See {@link #maxEndpointAge(Duration)}.
     */
    public OutlierDetectingEndpointGroupBuilder maxEndpointAgeMillis(long maxEndpointAgeMillis) {
        checkArgument(maxEndpointAgeMillis > 0,
                      "maxEndpointAgeMillis: %s (expected: > 0)", maxEndpointAgeMillis);
        this.maxEndpointAgeMillis = maxEndpointAgeMillis;
        return this;
    }

    /**
     * Sets the name prefix used when creating per-endpoint {@link CircuitBreaker}s. Defaults to
     * {@code "outlier-detecting"}.
     */
    public OutlierDetectingEndpointGroupBuilder namePrefix(String namePrefix) {
        requireNonNull(namePrefix, "namePrefix");
        checkArgument(!namePrefix.isEmpty(), "namePrefix cannot be empty");
        this.namePrefix = namePrefix;
        return this;
    }

    /**
     * Sets the failure rate threshold of the per-endpoint {@link CircuitBreaker}. When the failure rate
     * over the {@linkplain #counterSlidingWindow(Duration) counter sliding window} reaches this value, the
     * circuit opens and the endpoint is ejected. Defaults to {@code 0.5}.
     *
     * @param failureRateThreshold a rate in {@code (0, 1]}.
     * @throws IllegalArgumentException if {@code failureRateThreshold} is not in {@code (0, 1]}.
     */
    public OutlierDetectingEndpointGroupBuilder failureRateThreshold(double failureRateThreshold) {
        checkArgument(failureRateThreshold > 0 && failureRateThreshold <= 1,
                      "failureRateThreshold: %s (expected: > 0 and <= 1)", failureRateThreshold);
        this.failureRateThreshold = failureRateThreshold;
        return this;
    }

    /**
     * Sets the minimum number of requests within the {@linkplain #counterSlidingWindow(Duration) counter
     * sliding window} required before the per-endpoint {@link CircuitBreaker} can open. Defaults to
     * {@code 10}.
     *
     * @throws IllegalArgumentException if {@code minimumRequestThreshold} is negative.
     */
    public OutlierDetectingEndpointGroupBuilder minimumRequestThreshold(long minimumRequestThreshold) {
        checkArgument(minimumRequestThreshold >= 0,
                      "minimumRequestThreshold: %s (expected: >= 0)", minimumRequestThreshold);
        this.minimumRequestThreshold = minimumRequestThreshold;
        return this;
    }

    /**
     * Sets the interval at which a single trial request is allowed through while the per-endpoint
     * {@link CircuitBreaker} is in the {@code HALF_OPEN} state. Defaults to {@code 3} seconds.
     *
     * @throws NullPointerException if {@code trialRequestInterval} is {@code null}.
     * @throws IllegalArgumentException if {@code trialRequestInterval} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder trialRequestInterval(Duration trialRequestInterval) {
        requireNonNull(trialRequestInterval, "trialRequestInterval");
        return trialRequestIntervalMillis(trialRequestInterval.toMillis());
    }

    /**
     * Sets the {@linkplain #trialRequestInterval(Duration) trial request interval} in milliseconds.
     *
     * @throws IllegalArgumentException if {@code trialRequestIntervalMillis} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder trialRequestIntervalMillis(long trialRequestIntervalMillis) {
        checkArgument(trialRequestIntervalMillis > 0,
                      "trialRequestIntervalMillis: %s (expected: > 0)", trialRequestIntervalMillis);
        trialRequestInterval = Duration.ofMillis(trialRequestIntervalMillis);
        return this;
    }

    /**
     * Sets how long the per-endpoint {@link CircuitBreaker} stays in the {@code OPEN} state. The same
     * value is also used as the cool-down before a bad endpoint becomes selectable again. Defaults to
     * {@code 10} seconds.
     *
     * @throws NullPointerException if {@code circuitOpenWindow} is {@code null}.
     * @throws IllegalArgumentException if {@code circuitOpenWindow} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder circuitOpenWindow(Duration circuitOpenWindow) {
        requireNonNull(circuitOpenWindow, "circuitOpenWindow");
        return circuitOpenWindowMillis(circuitOpenWindow.toMillis());
    }

    /**
     * Sets the {@linkplain #circuitOpenWindow(Duration) circuit open window} in milliseconds.
     *
     * @throws IllegalArgumentException if {@code circuitOpenWindowMillis} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder circuitOpenWindowMillis(long circuitOpenWindowMillis) {
        checkArgument(circuitOpenWindowMillis > 0,
                      "circuitOpenWindowMillis: %s (expected: > 0)", circuitOpenWindowMillis);
        circuitOpenWindow = Duration.ofMillis(circuitOpenWindowMillis);
        return this;
    }

    /**
     * Sets the size of the rolling window over which the per-endpoint {@link CircuitBreaker} counts
     * successes and failures. Defaults to {@code 20} seconds.
     *
     * @throws NullPointerException if {@code counterSlidingWindow} is {@code null}.
     * @throws IllegalArgumentException if {@code counterSlidingWindow} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder counterSlidingWindow(Duration counterSlidingWindow) {
        requireNonNull(counterSlidingWindow, "counterSlidingWindow");
        return counterSlidingWindowMillis(counterSlidingWindow.toMillis());
    }

    /**
     * Sets the {@linkplain #counterSlidingWindow(Duration) counter sliding window} in milliseconds.
     *
     * @throws IllegalArgumentException if {@code counterSlidingWindowMillis} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder counterSlidingWindowMillis(long counterSlidingWindowMillis) {
        checkArgument(counterSlidingWindowMillis > 0,
                      "counterSlidingWindowMillis: %s (expected: > 0)", counterSlidingWindowMillis);
        counterSlidingWindow = Duration.ofMillis(counterSlidingWindowMillis);
        return this;
    }

    /**
     * Sets the interval at which the per-endpoint {@link CircuitBreaker} re-evaluates its counters and
     * may trip. Defaults to {@code 1} second.
     *
     * @throws NullPointerException if {@code counterUpdateInterval} is {@code null}.
     * @throws IllegalArgumentException if {@code counterUpdateInterval} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder counterUpdateInterval(Duration counterUpdateInterval) {
        requireNonNull(counterUpdateInterval, "counterUpdateInterval");
        return counterUpdateIntervalMillis(counterUpdateInterval.toMillis());
    }

    /**
     * Sets the {@linkplain #counterUpdateInterval(Duration) counter update interval} in milliseconds.
     *
     * @throws IllegalArgumentException if {@code counterUpdateIntervalMillis} is not positive.
     */
    public OutlierDetectingEndpointGroupBuilder counterUpdateIntervalMillis(long counterUpdateIntervalMillis) {
        checkArgument(counterUpdateIntervalMillis > 0,
                      "counterUpdateIntervalMillis: %s (expected: > 0)", counterUpdateIntervalMillis);
        counterUpdateInterval = Duration.ofMillis(counterUpdateIntervalMillis);
        return this;
    }

    /**
     * Controls behavior when every endpoint's circuit is open. By default ({@code false}), one of the
     * bad endpoints is picked as a last-resort fallback so traffic keeps flowing while the underlying
     * group recovers. When set to {@code true}, the fallback is disabled — endpoint selection then
     * waits for a healthy endpoint and fails if none becomes available within the
     * {@linkplain EndpointGroup#selectionTimeoutMillis() selection timeout}.
     */
    public OutlierDetectingEndpointGroupBuilder failFastOnAllCircuitOpen(boolean failFastOnAllCircuitOpen) {
        this.failFastOnAllCircuitOpen = failFastOnAllCircuitOpen;
        return this;
    }

    /**
     * Sets the {@link CircuitBreakerRule} used to classify each request outcome as a {@link CircuitBreaker}
     * success or failure. By default, 5xx responses and exceptions are reported as failures and everything
     * else as a success.
     */
    public OutlierDetectingEndpointGroupBuilder circuitBreakerRule(CircuitBreakerRule circuitBreakerRule) {
        this.circuitBreakerRule = requireNonNull(circuitBreakerRule, "circuitBreakerRule");
        return this;
    }

    /**
     * Registers gauges for the number of healthy and unhealthy endpoints under
     * {@code <meterIdPrefix>.endpoints.count}, distinguished by the {@code state} tag with values
     * {@code "healthy"} (endpoints currently in rotation) and {@code "unhealthy"} (endpoints whose
     * circuits are open and are temporarily ejected).
     */
    public OutlierDetectingEndpointGroupBuilder meterRegistry(MeterIdPrefix meterIdPrefix,
                                                              MeterRegistry meterRegistry) {
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return this;
    }

    /**
     * Returns a newly created {@link OutlierDetectingEndpointGroup}.
     */
    public OutlierDetectingEndpointGroup build() {
        if (counterSlidingWindow.compareTo(counterUpdateInterval) <= 0) {
            throw new IllegalStateException(
                    "counterSlidingWindow: " + counterSlidingWindow + " (expected: > counterUpdateInterval)");
        }
        final CircuitBreakerConfig circuitBreakerConfig = new CircuitBreakerConfig(
                null, failureRateThreshold, minimumRequestThreshold,
                circuitOpenWindow, trialRequestInterval,
                counterSlidingWindow, counterUpdateInterval, ImmutableList.of());
        return new OutlierDetectingEndpointGroup(delegate, maxNumEndpoints, maxEndpointAgeMillis,
                                                 namePrefix, failFastOnAllCircuitOpen, circuitBreakerRule,
                                                 circuitBreakerConfig, meterIdPrefix, meterRegistry);
    }
}
