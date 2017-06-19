/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measure;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;

public class MetricCollectingCircuitBreakerListenerTest {

    @Test
    public void test() throws Exception {
        final MeterRegistry registry = new PrometheusMeterRegistry();
        final CircuitBreakerListener l = new MetricCollectingCircuitBreakerListener(registry, "foo");

        // Note: We only use the name of the circuit breaker.
        final CircuitBreaker cb = new CircuitBreakerBuilder("bar").build();

        // Trigger the first event so that the metric group is registered.
        l.onEventCountUpdated(cb, new EventCount(1, 2));

        assertThat(measure(registry, "foo_requests", "name", "bar", "result", "success")).isEqualTo(1);
        assertThat(measure(registry, "foo_requests", "name", "bar", "result", "failure")).isEqualTo(2);
        assertThat(measure(registry, "foo_transitions_total", "name", "bar", "state", "CLOSED")).isZero();
        assertThat(measure(registry, "foo_transitions_total", "name", "bar", "state", "OPEN")).isZero();
        assertThat(measure(registry, "foo_transitions_total", "name", "bar", "state", "HALF_OPEN")).isZero();
        assertThat(measure(registry, "foo_rejected_requests_total", "name", "bar")).isZero();

        // Transit to CLOSED.
        l.onStateChanged(cb, CircuitState.CLOSED);
        assertThat(measure(registry, "foo_transitions_total", "name", "bar", "state", "CLOSED")).isOne();

        // Transit to OPEN.
        l.onStateChanged(cb, CircuitState.OPEN);
        assertThat(measure(registry, "foo_transitions_total", "name", "bar", "state", "OPEN")).isOne();

        // Transit to HALF_OPEN.
        l.onStateChanged(cb, CircuitState.HALF_OPEN);
        assertThat(measure(registry, "foo_transitions_total", "name", "bar", "state", "HALF_OPEN")).isOne();

        // Reject a request.
        l.onRequestRejected(cb);
        assertThat(measure(registry, "foo_rejected_requests_total", "name", "bar")).isOne();
    }
}
