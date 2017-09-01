/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;

import io.micrometer.core.instrument.MeterRegistry;

public class MetricCollectingCircuitBreakerListenerTest {

    @Test
    public void test() throws Exception {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final CircuitBreakerListener l = new MetricCollectingCircuitBreakerListener(registry, "foo");

        // Note: We only use the name of the circuit breaker.
        final CircuitBreaker cb = new CircuitBreakerBuilder("bar").build();

        // Trigger the first event so that the metric group is registered.
        l.onEventCountUpdated(cb, new EventCount(1, 2));

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.requests#value{name=bar,result=success}", 1.0)
                .containsEntry("foo.requests#value{name=bar,result=failure}", 2.0)
                .containsEntry("foo.transitions#count{name=bar,state=CLOSED}", 0.0)
                .containsEntry("foo.transitions#count{name=bar,state=OPEN}", 0.0)
                .containsEntry("foo.transitions#count{name=bar,state=HALF_OPEN}", 0.0)
                .containsEntry("foo.rejectedRequests#count{name=bar}", 0.0);

        // Transit to CLOSED.
        l.onStateChanged(cb, CircuitState.CLOSED);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.transitions#count{name=bar,state=CLOSED}", 1.0);

        // Transit to OPEN.
        l.onStateChanged(cb, CircuitState.OPEN);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.transitions#count{name=bar,state=OPEN}", 1.0);

        // Transit to HALF_OPEN.
        l.onStateChanged(cb, CircuitState.HALF_OPEN);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.transitions#count{name=bar,state=HALF_OPEN}", 1.0);

        // Reject a request.
        l.onRequestRejected(cb);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.rejectedRequests#count{name=bar}", 1.0);
    }
}
