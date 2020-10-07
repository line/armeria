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

import static com.linecorp.armeria.client.circuitbreaker.CircuitState.CLOSED;
import static com.linecorp.armeria.client.circuitbreaker.CircuitState.HALF_OPEN;
import static com.linecorp.armeria.client.circuitbreaker.CircuitState.OPEN;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicReference;

import com.google.common.util.concurrent.AtomicDouble;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Provides {@link CircuitBreaker} stats.
 */
final class CircuitBreakerMetrics {

    private final AtomicReference<EventCount> latestEventCount = new AtomicReference<>(EventCount.ZERO);
    private final AtomicDouble state = new AtomicDouble(1);
    private final Counter transitionsToClosed;
    private final Counter transitionsToOpen;
    private final Counter transitionsToHalfOpen;
    private final Counter rejectedRequests;

    CircuitBreakerMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
        requireNonNull(parent, "parent");
        requireNonNull(idPrefix, "idPrefix");

        parent.gauge(idPrefix.name("state"), idPrefix.tags(), state, AtomicDouble::get);

        final String requests = idPrefix.name("requests");
        parent.gauge(requests, idPrefix.tags("result", "success"),
                     latestEventCount, lec -> lec.get().success());
        parent.gauge(requests, idPrefix.tags("result", "failure"),
                     latestEventCount, lec -> lec.get().failure());

        final String transitions = idPrefix.name("transitions");
        transitionsToClosed = parent.counter(transitions, idPrefix.tags("state", CLOSED.name()));
        transitionsToOpen = parent.counter(transitions, idPrefix.tags("state", OPEN.name()));
        transitionsToHalfOpen = parent.counter(transitions, idPrefix.tags("state", HALF_OPEN.name()));
        rejectedRequests = parent.counter(idPrefix.name("rejected.requests"), idPrefix.tags());
    }

    void onStateChanged(CircuitState state) {
        switch (state) {
            case CLOSED:
                this.state.set(1);
                transitionsToClosed.increment();
                break;
            case OPEN:
                this.state.set(0);
                transitionsToOpen.increment();
                break;
            case HALF_OPEN:
                this.state.set(0.5);
                transitionsToHalfOpen.increment();
                break;
            default:
                throw new Error("unknown circuit state: " + state);
        }
    }

    void onCountUpdated(EventCount count) {
        latestEventCount.set(count);
    }

    void onRequestRejected() {
        rejectedRequests.increment();
    }
}
