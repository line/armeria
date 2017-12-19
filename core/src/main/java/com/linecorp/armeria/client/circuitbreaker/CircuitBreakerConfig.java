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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.google.common.base.MoreObjects;

/**
 * Stores configurations of circuit breaker.
 */
class CircuitBreakerConfig {

    private final Optional<String> name;

    private final double failureRateThreshold;

    private final long minimumRequestThreshold;

    private final Duration circuitOpenWindow;

    private final Duration trialRequestInterval;

    private final Duration counterSlidingWindow;

    private final Duration counterUpdateInterval;

    private final ExceptionFilter exceptionFilter;

    private final List<CircuitBreakerListener> listeners;

    CircuitBreakerConfig(Optional<String> name,
                         double failureRateThreshold, long minimumRequestThreshold,
                         Duration circuitOpenWindow, Duration trialRequestInterval,
                         Duration counterSlidingWindow, Duration counterUpdateInterval,
                         ExceptionFilter exceptionFilter, List<CircuitBreakerListener> listeners) {
        this.name = name;
        this.failureRateThreshold = failureRateThreshold;
        this.minimumRequestThreshold = minimumRequestThreshold;
        this.circuitOpenWindow = circuitOpenWindow;
        this.trialRequestInterval = trialRequestInterval;
        this.counterSlidingWindow = counterSlidingWindow;
        this.counterUpdateInterval = counterUpdateInterval;
        this.exceptionFilter = exceptionFilter;
        this.listeners = listeners;
    }

    Optional<String> name() {
        return name;
    }

    double failureRateThreshold() {
        return failureRateThreshold;
    }

    long minimumRequestThreshold() {
        return minimumRequestThreshold;
    }

    Duration circuitOpenWindow() {
        return circuitOpenWindow;
    }

    Duration trialRequestInterval() {
        return trialRequestInterval;
    }

    Duration counterSlidingWindow() {
        return counterSlidingWindow;
    }

    Duration counterUpdateInterval() {
        return counterUpdateInterval;
    }

    ExceptionFilter exceptionFilter() {
        return exceptionFilter;
    }

    List<CircuitBreakerListener> listeners() {
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
