/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker.metrics;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicReference;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.RatioGauge;

import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.client.circuitbreaker.EventCount;

/**
 * Stores dropwirzard's metrics tracking circuit breaker events.
 */
final class DropwizardCircuitBreakerMetrics {

    private final AtomicReference<EventCount> latestEventCount = new AtomicReference<>(EventCount.ZERO);

    private final Meter closed;

    private final Meter halfOpen;

    private final Meter open;

    private final Meter requestRejected;

    DropwizardCircuitBreakerMetrics(String prefix, String name, MetricRegistry registry) {
        requireNonNull(prefix, "prefix");
        requireNonNull(name, "name");
        requireNonNull(registry, "registry");

        // {prefix}.{name}.counter.rate.success
        registry.register(MetricRegistry.name(prefix, name, "counter", "rate", "success"), new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                final EventCount currentCount = latestEventCount.get();
                return Ratio.of(currentCount.success(), currentCount.total());
            }
        });

        // {prefix}.{name}.counter.rate.failure
        registry.register(MetricRegistry.name(prefix, name, "counter", "rate", "failure"), new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                final EventCount currentCount = latestEventCount.get();
                return Ratio.of(currentCount.failure(), currentCount.total());
            }
        });

        // {prefix}.{name}.counter.count.success
        final Gauge<Long> successCount = () -> latestEventCount.get().success();
        registry.register(MetricRegistry.name(prefix, name, "counter", "count", "success"), successCount);

        // {prefix}.{name}.counter.count.failure
        final Gauge<Long> failureCount = () -> latestEventCount.get().failure();
        registry.register(MetricRegistry.name(prefix, name, "counter", "count", "failure"), failureCount);

        // {prefix}.{name}.transitions.closed
        closed = registry.meter(MetricRegistry.name(prefix, name, "transitions", "closed"));

        // {prefix}.{name}.transitions.halfopen
        halfOpen = registry.meter(MetricRegistry.name(prefix, name, "transitions", "halfopen"));

        // {prefix}.{name}.transitions.open
        open = registry.meter(MetricRegistry.name(prefix, name, "transitions", "open"));

        // {prefix}.{name}.requests.rejected
        requestRejected = registry.meter(MetricRegistry.name(prefix, name, "requests", "rejected"));
    }

    void onStateChanged(CircuitState state) {
        if (state == CircuitState.CLOSED) {
            closed.mark();
        } else if (state == CircuitState.HALF_OPEN) {
            halfOpen.mark();
        } else if (state == CircuitState.OPEN) {
            open.mark();
        }
    }

    void onCountUpdated(EventCount count) {
        latestEventCount.set(count);
    }

    void onRequestRejected() {
        requestRejected.mark();
    }

}
