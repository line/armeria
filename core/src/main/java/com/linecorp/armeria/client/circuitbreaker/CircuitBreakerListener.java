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

import static com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener.DEFAULT_METER_NAME;
import static com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener.LEGACY_METER_NAME;

import com.linecorp.armeria.common.Flags;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The listener interface for receiving {@link CircuitBreaker} events.
 */
public interface CircuitBreakerListener {

    /**
     * Returns a new {@link CircuitBreakerListener} that collects metric with the specified
     * {@link MeterRegistry}.
     */
    static CircuitBreakerListener metricCollecting(MeterRegistry registry) {
        return metricCollecting(registry, Flags.useLegacyMeterNames() ? LEGACY_METER_NAME : DEFAULT_METER_NAME);
    }

    /**
     * Returns a new {@link CircuitBreakerListener} that collects metric with the specified
     * {@link MeterRegistry} and {@link Meter} name.
     */
    static CircuitBreakerListener metricCollecting(MeterRegistry registry, String name) {
        return new MetricCollectingCircuitBreakerListener(registry, name);
    }

    /**
     * Invoked when the circuit breaker is initialized.
     */
    default void onInitialized(String circuitBreakerName, CircuitState initialState) throws Exception {
        onStateChanged(circuitBreakerName, initialState);
    }

    /**
     * Invoked when the circuit state is changed.
     */
    void onStateChanged(String circuitBreakerName, CircuitState state) throws Exception;

    /**
     * Invoked when the circuit breaker's internal {@link EventCount} is updated.
     */
    void onEventCountUpdated(String circuitBreakerName, EventCount eventCount) throws Exception;

    /**
     * Invoked when the circuit breaker rejects a request.
     */
    void onRequestRejected(String circuitBreakerName) throws Exception;
}
