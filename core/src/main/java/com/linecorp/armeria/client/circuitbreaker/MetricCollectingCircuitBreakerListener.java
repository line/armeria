/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.metric.MicrometerUtil;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * A {@link CircuitBreakerListener} which exports the status of {@link CircuitBreaker}s to
 * {@link MeterRegistry}.
 *
 * @see CircuitBreakerListener#metricCollecting(MeterRegistry)
 * @see CircuitBreakerListener#metricCollecting(MeterRegistry, String)
 */
final class MetricCollectingCircuitBreakerListener implements CircuitBreakerListener {

    private final MeterRegistry registry;
    private final String name;

    /**
     * Creates a new instance with the specified {@link Meter} name.
     */
    MetricCollectingCircuitBreakerListener(MeterRegistry registry, String name) {
        this.registry = requireNonNull(registry, "registry");
        this.name = requireNonNull(name, "name");
    }

    @Override
    public void onStateChanged(String circuitBreakerName, CircuitState state) {
        metricsOf(circuitBreakerName).onStateChanged(state);
    }

    @Override
    public void onEventCountUpdated(String circuitBreakerName, EventCount eventCount) {
        metricsOf(circuitBreakerName).onCountUpdated(eventCount);
    }

    @Override
    public void onRequestRejected(String circuitBreakerName) {
        metricsOf(circuitBreakerName).onRequestRejected();
    }

    private CircuitBreakerMetrics metricsOf(String circuitBreakerName) {
        final MeterIdPrefix idPrefix = new MeterIdPrefix(name, "name", circuitBreakerName);
        return MicrometerUtil.register(registry, idPrefix,
                                       CircuitBreakerMetrics.class,
                                       CircuitBreakerMetrics::new);
    }
}
