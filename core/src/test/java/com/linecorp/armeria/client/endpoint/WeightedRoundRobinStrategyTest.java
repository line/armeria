/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class WeightedRoundRobinStrategyTest {
    private static final EndpointGroup ENDPOINT_GROUP =
            EndpointGroup.of(Endpoint.parse("localhost:1234"), Endpoint.parse("localhost:2345"));

    private static final EndpointGroup EMPTY_ENDPOINT_GROUP = EndpointGroup.empty();

    private final WeightedRoundRobinStrategy strategy = new WeightedRoundRobinStrategy();

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @BeforeEach
    void setup() {
        EndpointGroupRegistry.register("endpoint", ENDPOINT_GROUP, strategy);
        EndpointGroupRegistry.register("empty", EMPTY_ENDPOINT_GROUP, strategy);
    }

    @Test
    void select() {
        assertThat(EndpointGroupRegistry.selectNode(ctx, "endpoint")).isNotNull();

        assertThat(catchThrowable(() -> EndpointGroupRegistry.selectNode(ctx, "empty")))
                .isInstanceOf(EndpointGroupException.class);
    }

    @Test
    void testRoundRobinSelect() {
        final EndpointGroup endpointGroup = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234),
                Endpoint.of("127.0.0.1", 2345),
                Endpoint.of("127.0.0.1", 3456));
        final String groupName = "roundRobin";

        EndpointGroupRegistry.register(groupName, endpointGroup, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
    }

    @Test
    void testWeightedRoundRobinSelect() {
        final String groupName = "weighted";

        //weight 1,2,3
        final EndpointGroup endpointGroup = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(1),
                Endpoint.of("127.0.0.1", 2345).withWeight(2),
                Endpoint.of("127.0.0.1", 3456).withWeight(3));
        EndpointGroupRegistry.register(groupName, endpointGroup, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");

        //weight 3,2,2
        final EndpointGroup endpointGroup2 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(3),
                Endpoint.of("127.0.0.1", 2345).withWeight(2),
                Endpoint.of("127.0.0.1", 3456).withWeight(2));
        EndpointGroupRegistry.register(groupName, endpointGroup2, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        //new round
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");

        //weight 4,4,4
        final EndpointGroup endpointGroup3 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(4),
                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                Endpoint.of("127.0.0.1", 3456).withWeight(4));
        EndpointGroupRegistry.register(groupName, endpointGroup3, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");

        //weight 2,4,6
        final EndpointGroup endpointGroup4 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(2),
                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                Endpoint.of("127.0.0.1", 3456).withWeight(6));
        EndpointGroupRegistry.register(groupName, endpointGroup4, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        //new round
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");

        //weight 4,6,2
        final EndpointGroup endpointGroup5 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                Endpoint.of("127.0.0.1", 3456).withWeight(6),
                Endpoint.of("127.0.0.1", 1234).withWeight(2));
        EndpointGroupRegistry.register(groupName, endpointGroup5, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        //new round
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");

        //weight dynamic with random weight
        final Random rnd = new Random();

        final DynamicEndpointGroup dynamic = new DynamicEndpointGroup();
        final int numberOfEndpoint = 500;
        final int[] weights = new int[numberOfEndpoint];

        long totalWeight = 0;
        for (int i = 0; i < numberOfEndpoint; i++) {
            weights[i] = i == 0 ? weights[i] : weights[i - 1] + rnd.nextInt(100);
            totalWeight += weights[i];
            dynamic.addEndpoint(
                    Endpoint.of("127.0.0.1", i + 1).withWeight(weights[i])
            );
        }
        EndpointGroupRegistry.register(groupName, dynamic, WEIGHTED_ROUND_ROBIN);

        int chosen = 0;
        while (totalWeight-- > 0) {
            while (weights[chosen] == 0) {
                chosen = (chosen + 1) % numberOfEndpoint;
            }

            assertThat(EndpointGroupRegistry
                    .selectNode(ctx, groupName).authority())
                    .isEqualTo("127.0.0.1:" + (chosen + 1));
            weights[chosen]--;

            chosen = (chosen + 1) % numberOfEndpoint;
        }
    }

    @Test
    void selectFromDynamicEndpointGroup() {
        final TestDynamicEndpointGroup endpointGroup = new TestDynamicEndpointGroup();
        EndpointGroupRegistry.register("dynamic", endpointGroup, strategy);
        endpointGroup.updateEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1000)));

        final EndpointSelector selector = EndpointGroupRegistry.getNodeSelector("dynamic");
        assertThat(selector).isNotNull();
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1000));

        endpointGroup.updateEndpoints(ImmutableList.of(
                Endpoint.of("127.0.0.1", 1111).withWeight(1),
                Endpoint.of("127.0.0.1", 2222).withWeight(2))
        );

        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1111).withWeight(1));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1111).withWeight(1));
    }

    private static final class TestDynamicEndpointGroup extends DynamicEndpointGroup {
        void updateEndpoints(List<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }
    }
}
