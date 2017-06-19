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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.metric.MetricKey;
import com.linecorp.armeria.common.metric.Metrics;

public class MetricCollectingCircuitBreakerListenerTest {

    @Test
    public void test() throws Exception {
        final Metrics metrics = new Metrics();
        final CircuitBreakerListener l = new MetricCollectingCircuitBreakerListener(metrics, "foo");

        // Note: We only use the name of the circuit breaker.
        final CircuitBreaker cb = new CircuitBreakerBuilder("bar").build();

        // Trigger the first event so that the metric group is registered.
        l.onEventCountUpdated(cb, new EventCount(1, 2));

        final CircuitBreakerMetrics cbm = metrics.group(new MetricKey("foo", "bar"),
                                                        CircuitBreakerMetrics.class);
        assertThat(cbm).isNotNull();
        assertThat(cbm.key()).isEqualTo(new MetricKey("foo", "bar"));
        assertThat(cbm.success().value()).isEqualTo(1);
        assertThat(cbm.failure().value()).isEqualTo(2);
        assertThat(cbm.total().value()).isEqualTo(3);
        assertThat(cbm.transitionToClosed().value()).isZero();
        assertThat(cbm.transitionToOpen().value()).isZero();
        assertThat(cbm.transitionToHalfOpen().value()).isZero();
        assertThat(cbm.rejectedRequest().value()).isZero();

        // Transit to CLOSED.
        l.onStateChanged(cb, CircuitState.CLOSED);
        assertThat(cbm.transitionToClosed().value()).isOne();

        // Transit to OPEN.
        l.onStateChanged(cb, CircuitState.OPEN);
        assertThat(cbm.transitionToOpen().value()).isOne();

        // Transit to HALF_OPEN.
        l.onStateChanged(cb, CircuitState.HALF_OPEN);
        assertThat(cbm.transitionToHalfOpen().value()).isOne();

        // Reject a request.
        l.onRequestRejected(cb);
        assertThat(cbm.rejectedRequest().value()).isOne();
    }
}
