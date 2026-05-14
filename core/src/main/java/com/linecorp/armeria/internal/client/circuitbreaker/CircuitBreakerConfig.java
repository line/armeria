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

package com.linecorp.armeria.internal.client.circuitbreaker;

import java.time.Duration;
import java.util.List;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListener;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Stores configurations of circuit breaker.
 */
public final class CircuitBreakerConfig {

    public static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.5;
    public static final long DEFAULT_MINIMUM_REQUEST_THRESHOLD = 10;
    public static final int DEFAULT_TRIAL_REQUEST_INTERVAL_SECONDS = 3;
    public static final int DEFAULT_CIRCUIT_OPEN_WINDOW_SECONDS = 10;
    public static final int DEFAULT_COUNTER_SLIDING_WINDOW_SECONDS = 20;
    public static final int DEFAULT_COUNTER_UPDATE_INTERVAL_SECONDS = 1;

    @Nullable
    private final String name;

    private final double failureRateThreshold;

    private final long minimumRequestThreshold;

    private final Duration circuitOpenWindow;

    private final Duration trialRequestInterval;

    private final Duration counterSlidingWindow;

    private final Duration counterUpdateInterval;

    private final List<CircuitBreakerListener> listeners;

    public CircuitBreakerConfig(@Nullable String name,
                                double failureRateThreshold, long minimumRequestThreshold,
                                Duration circuitOpenWindow, Duration trialRequestInterval,
                                Duration counterSlidingWindow, Duration counterUpdateInterval,
                                List<CircuitBreakerListener> listeners) {
        this.name = name;
        this.failureRateThreshold = failureRateThreshold;
        this.minimumRequestThreshold = minimumRequestThreshold;
        this.circuitOpenWindow = circuitOpenWindow;
        this.trialRequestInterval = trialRequestInterval;
        this.counterSlidingWindow = counterSlidingWindow;
        this.counterUpdateInterval = counterUpdateInterval;
        this.listeners = listeners;
    }

    @Nullable
    public String name() {
        return name;
    }

    public double failureRateThreshold() {
        return failureRateThreshold;
    }

    public long minimumRequestThreshold() {
        return minimumRequestThreshold;
    }

    public Duration circuitOpenWindow() {
        return circuitOpenWindow;
    }

    public Duration trialRequestInterval() {
        return trialRequestInterval;
    }

    public Duration counterSlidingWindow() {
        return counterSlidingWindow;
    }

    public Duration counterUpdateInterval() {
        return counterUpdateInterval;
    }

    public List<CircuitBreakerListener> listeners() {
        return listeners;
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("name", name)
                .add("failureRateThreshold", failureRateThreshold)
                .add("minimumRequestThreshold", minimumRequestThreshold)
                .add("circuitOpenWindow", circuitOpenWindow)
                .add("trialRequestInterval", trialRequestInterval)
                .add("counterSlidingWindow", counterSlidingWindow)
                .add("counterUpdateInterval", counterUpdateInterval)
                .toString();
    }
}
