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

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicReference;

import com.linecorp.armeria.common.metric.Gauge;
import com.linecorp.armeria.common.metric.LongAdderGauge;
import com.linecorp.armeria.common.metric.MetricKey;
import com.linecorp.armeria.common.metric.MetricUnit;
import com.linecorp.armeria.common.metric.Metrics;
import com.linecorp.armeria.common.metric.SupplierGauge;

/**
 * Default {@link CircuitBreakerMetrics} implementation.
 */
final class DefaultCircuitBreakerMetrics implements CircuitBreakerMetrics {

    private final MetricKey key;
    private final AtomicReference<EventCount> latestEventCount = new AtomicReference<>(EventCount.ZERO);
    private final Gauge success;
    private final Gauge failure;
    private final Gauge total;
    private final LongAdderGauge transitionToClosed;
    private final LongAdderGauge transitionToOpen;
    private final LongAdderGauge transitionToHalfOpen;
    private final LongAdderGauge rejectedRequest;

    DefaultCircuitBreakerMetrics(Metrics parent, MetricKey key) {
        requireNonNull(parent, "parent");
        this.key = requireNonNull(key, "key");

        success = parent.register(new SupplierGauge(
                key.append("success"), MetricUnit.COUNT,
                "the number of successful requests in the counter time window",
                () -> latestEventCount.get().success()));
        failure = parent.register(new SupplierGauge(
                key.append("failure"), MetricUnit.COUNT,
                "the number of failed requests in the counter time window",
                () -> latestEventCount.get().failure()));
        total = parent.register(new SupplierGauge(
                key.append("total"), MetricUnit.COUNT,
                "the number of requests in the counter time window",
                () -> success.value() + failure.value()));

        transitionToClosed = parent.register(new LongAdderGauge(
                key.append("transitionToClosed"), MetricUnit.COUNT_CUMULATIVE,
                "the number of circuit state transitions to CLOSED"));
        transitionToOpen = parent.register(new LongAdderGauge(
                key.append("transitionToOpen"), MetricUnit.COUNT_CUMULATIVE,
                "the number of circuit state transitions to OPEN"));
        transitionToHalfOpen = parent.register(new LongAdderGauge(
                key.append("transitionToHalfOpen"), MetricUnit.COUNT_CUMULATIVE,
                "the number of circuit state transitions to HALF_OPEN"));

        rejectedRequest = parent.register(new LongAdderGauge(
                key.append("rejectedRequest"), MetricUnit.COUNT_CUMULATIVE,
                "the number of requests rejected by the circuit breaker"));
    }

    @Override
    public MetricKey key() {
        return key;
    }

    @Override
    public Gauge total() {
        return total;
    }

    @Override
    public Gauge success() {
        return success;
    }

    @Override
    public Gauge failure() {
        return failure;
    }

    @Override
    public Gauge transitionToClosed() {
        return transitionToClosed;
    }

    @Override
    public Gauge transitionToOpen() {
        return transitionToOpen;
    }

    @Override
    public Gauge transitionToHalfOpen() {
        return transitionToHalfOpen;
    }

    @Override
    public Gauge rejectedRequest() {
        return rejectedRequest;
    }

    void onStateChanged(CircuitState state) {
        if (state == CircuitState.CLOSED) {
            transitionToClosed.inc();
        } else if (state == CircuitState.HALF_OPEN) {
            transitionToHalfOpen.inc();
        } else if (state == CircuitState.OPEN) {
            transitionToOpen.inc();
        } else {
            throw new Error("unknown circuit state: " + state);
        }
    }

    void onCountUpdated(EventCount count) {
        latestEventCount.set(count);
    }

    void onRequestRejected() {
        rejectedRequest.inc();
    }
}
