/*
 * Copyright 2019 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.SlowStartAwareEndpointSelectionStrategy.EndpointWeighter;
import com.linecorp.armeria.client.endpoint.WeightedRoundRobinStrategyTest.TestDynamicEndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class SlowStartAwareEndpointSelectionStrategyTest {
    private static final EndpointGroup ENDPOINT_GROUP =
            EndpointGroup.of(Endpoint.parse("localhost:1234").withWeight(1000),
                             Endpoint.parse("localhost:2345").withWeight(1000));

    private static final EndpointWeighter weighter = EndpointWeighter.DEFAULT;

    private static final SlowStartAwareEndpointSelectionStrategy strategy =
            new SlowStartAwareEndpointSelectionStrategy(weighter,
                                                        ClientFactory.DEFAULT,
                                                        Duration.ofSeconds(1),
                                                        10);

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @BeforeEach
    void setup() {
        EndpointGroupRegistry.register("endpoint", ENDPOINT_GROUP, strategy);
    }

    @Test
    void roundRobinSelect() {
        final EndpointGroup endpointGroup = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234),
                Endpoint.of("127.0.0.1", 2345),
                Endpoint.of("127.0.0.1", 3456));
        final String groupName = "roundRobin";

        EndpointGroupRegistry.register(groupName, endpointGroup, strategy);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
    }

    @Test
    void weightedRoundRobin() {
        final EndpointGroup endpointGroup = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(1),
                Endpoint.of("127.0.0.1", 2345).withWeight(2),
                Endpoint.of("127.0.0.1", 3456).withWeight(3));
        final String groupName = "weighted";

        EndpointGroupRegistry.register(groupName, endpointGroup, strategy);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
    }

    @Test
    void selectFromDynamicEndpointGroup() throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        final EndpointWeighter weighter = (e, step, maxStep) -> {
            counter.incrementAndGet();
            return EndpointWeighter.DEFAULT.compute(e, step, maxStep);
        };
        final SlowStartAwareEndpointSelectionStrategy strategy =
                new SlowStartAwareEndpointSelectionStrategy(weighter,
                                                            ClientFactory.DEFAULT,
                                                            Duration.ofSeconds(1),
                                                            10);

        final TestDynamicEndpointGroup endpointGroup = new TestDynamicEndpointGroup();
        EndpointGroupRegistry.register("dynamic", endpointGroup, strategy);
        endpointGroup.updateEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1000)));

        final EndpointSelector selector = EndpointGroupRegistry.getNodeSelector("dynamic");
        // Schedule updateEndpointWeight.
        TimeUnit.SECONDS.sleep(1);
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1000));

        endpointGroup.updateEndpoints(ImmutableList.of(
                Endpoint.of("127.0.0.1", 1111).withWeight(100)));

        await().atMost(Duration.ofSeconds(1))
               .untilAsserted(() -> {
                   final Endpoint e = selector.select(ctx);
                   assertThat(e.host()).isEqualTo("127.0.0.1");
                   assertThat(e.port()).isEqualTo(1111);
                   assertThat(e.weight()).isGreaterThanOrEqualTo(1);
               });
        await().atMost(Duration.ofSeconds(1))
               .untilAsserted(() -> assertThat(selector.select(ctx).weight()).isGreaterThanOrEqualTo(10));
        await().atMost(Duration.ofSeconds(1))
               .untilAsserted(() -> assertThat(selector.select(ctx).weight()).isGreaterThanOrEqualTo(20));
        await().atMost(Duration.ofSeconds(1))
               .untilAsserted(() -> assertThat(selector.select(ctx).weight()).isGreaterThanOrEqualTo(30));
        // Set known endpoint but the weight continue to rump up with previous weight.
        endpointGroup.updateEndpoints(ImmutableList.of(
                Endpoint.of("127.0.0.1", 1111).withWeight(100)));
        await().atMost(Duration.ofSeconds(1))
               .untilAsserted(() -> assertThat(selector.select(ctx).weight()).isGreaterThanOrEqualTo(40));
    }
}
