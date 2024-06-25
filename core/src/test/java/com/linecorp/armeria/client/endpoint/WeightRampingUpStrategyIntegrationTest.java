/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class WeightRampingUpStrategyIntegrationTest {

    static {
        CommonPools.workerGroup().next().execute(() -> {});
    }
    private final long rampingUpIntervalNanos = TimeUnit.MILLISECONDS.toNanos(1000);
    private final long rampingUpTaskWindowNanos = TimeUnit.MILLISECONDS.toNanos(200);
    private final ClientRequestContext ctx = ClientRequestContext.of(
            HttpRequest.of(HttpMethod.GET, "/"));

    private final Endpoint endpointA = Endpoint.of("a.com");
    private final Endpoint endpointB = Endpoint.of("b.com");
    private final Endpoint endpointC = Endpoint.of("c.com");
    private final Endpoint endpointFoo = Endpoint.of("foo.com");
    private final Endpoint endpointFoo1 = Endpoint.of("foo1.com");

    @Test
    void endpointIsRemovedIfNotInNewEndpoints() {
        final DynamicEndpointGroup endpointGroup = newEndpointGroup();
        setInitialEndpoints(endpointGroup);
        final Map<Endpoint, Integer> counter = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            final Endpoint endpoint = endpointGroup.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            counter.compute(endpoint, (k, v) -> v == null ? 1 : v + 1);
        }
        assertThat(counter.get(endpointFoo)).isCloseTo(1000, Offset.offset(100));
        assertThat(counter.get(endpointFoo1)).isCloseTo(1000, Offset.offset(100));
        // Because we set only foo1.com, foo.com is removed.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("foo1.com")));
        final Endpoint endpoint3 = endpointGroup.selectNow(ctx);
        final Endpoint endpoint4 = endpointGroup.selectNow(ctx);
        assertThat(ImmutableList.of(endpoint3, endpoint4)).usingElementComparator(EndpointComparator.INSTANCE)
                                                          .containsExactly(Endpoint.of("foo1.com"),
                                                                           Endpoint.of("foo1.com"));
    }

    @Test
    void testSlowStart() throws InterruptedException {
        final DynamicEndpointGroup endpointGroup = newEndpointGroup();
        endpointGroup.setEndpoints(ImmutableList.of(endpointA, endpointB));
        // Initialize RampingUpLoadBalancer
        endpointGroup.selectNow(ctx);
        // Waits for the ramping-up to be completed.
        Thread.sleep(5000);

        // Start ramping-up and measure the weights
        endpointGroup.addEndpoint(endpointC);
        for (int round = 1; round <= 5; round++) {
            measureRampingUp(endpointGroup, round);
            Thread.sleep(1000);
        }
    }

    private void measureRampingUp(EndpointGroup endpointGroup, int round) {
        final Map<Endpoint, Integer> counter = new HashMap<>();
        final int slowStartWeight = 200 * round;
        // 1st ramping-up
        for (int i = 0; i < 2000 + slowStartWeight; i++) {
            final Endpoint endpoint = endpointGroup.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            counter.compute(endpoint, (k, v) -> v == null ? 1 : v + 1);
        }
        assertThat(counter.get(endpointA)).isCloseTo(1000, Offset.offset(100));
        assertThat(counter.get(endpointB)).isCloseTo(1000, Offset.offset(100));
        assertThat(counter.get(endpointC)).isCloseTo(slowStartWeight, Offset.offset(100));
    }

    private DynamicEndpointGroup newEndpointGroup() {
        final EndpointSelectionStrategy weightRampingUpStrategy =
                EndpointSelectionStrategy.builderForRampingUp()
                                         .rampingUpInterval(Duration.ofNanos(rampingUpIntervalNanos))
                                         .rampingUpTaskWindow(Duration.ofNanos(rampingUpTaskWindowNanos))
                                         .totalSteps(5)
                                         .build();
        return new DynamicEndpointGroup(weightRampingUpStrategy);
    }

    private void setInitialEndpoints(DynamicEndpointGroup endpointGroup) {
        final List<Endpoint> endpoints = ImmutableList.of(endpointFoo, endpointFoo1);
        endpointGroup.setEndpoints(endpoints);
    }

    /**
     * A Comparator which includes the weight of an endpoint to compare.
     */
    enum EndpointComparator implements Comparator<Endpoint> {

        INSTANCE;

        @Override
        public int compare(Endpoint o1, Endpoint o2) {
            if (o1.equals(o2) && o1.weight() == o2.weight()) {
                return 0;
            }
            return -1;
        }
    }
}
