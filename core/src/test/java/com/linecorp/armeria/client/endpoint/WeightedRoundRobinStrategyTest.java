/*
 * Copyright 2016 LINE Corporation
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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;

public class WeightedRoundRobinStrategyTest {
    private static final EndpointGroup ENDPOINT_GROUP = new StaticEndpointGroup(Endpoint.of("localhost:1234"),
                                                                                Endpoint.of("localhost:2345"));
    private static final EndpointGroup EMPTY_ENDPOINT_GROUP = new StaticEndpointGroup();

    private final WeightedRoundRobinStrategy strategy = new WeightedRoundRobinStrategy();

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ClientRequestContext ctx;

    @Before
    public void setup() {
        EndpointGroupRegistry.register("endpoint", ENDPOINT_GROUP, strategy);
        EndpointGroupRegistry.register("empty", EMPTY_ENDPOINT_GROUP, strategy);
    }

    @Test
    public void select() {
        assertThat(EndpointGroupRegistry.selectNode(ctx, "endpoint")).isNotNull();

        assertThat(catchThrowable(() -> EndpointGroupRegistry.selectNode(ctx, "empty")))
                .isInstanceOf(EndpointGroupException.class);
    }

    @Test
    public void testRoundRobinSelect() {
        EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234),
                Endpoint.of("127.0.0.1", 2345),
                Endpoint.of("127.0.0.1", 3456));
        String groupName = "roundRobin";

        EndpointGroupRegistry.register(groupName, endpointGroup, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
    }

    @Test
    public void testWeightedRoundRobinSelect() {
        //weight 1,2,3
        EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 1),
                Endpoint.of("127.0.0.1", 2345, 2),
                Endpoint.of("127.0.0.1", 3456, 3));
        String groupName = "weighted";

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
        EndpointGroup endpointGroup2 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 3),
                Endpoint.of("127.0.0.1", 2345, 2),
                Endpoint.of("127.0.0.1", 3456, 2));
        EndpointGroupRegistry.register(groupName, endpointGroup2, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");

        //weight 4,4,4
        EndpointGroup endpointGroup3 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 4),
                Endpoint.of("127.0.0.1", 2345, 4),
                Endpoint.of("127.0.0.1", 3456, 4));
        EndpointGroupRegistry.register(groupName, endpointGroup3, WEIGHTED_ROUND_ROBIN);

        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(EndpointGroupRegistry.selectNode(ctx, groupName).authority()).isEqualTo("127.0.0.1:3456");

        //weight 2,4,6
        EndpointGroup endpointGroup4 = new StaticEndpointGroup(
                Endpoint.of("127.0.0.1", 1234, 2),
                Endpoint.of("127.0.0.1", 2345, 4),
                Endpoint.of("127.0.0.1", 3456, 6));
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
    }

    @Test
    public void selectFromDynamicEndpointGroup() {
        TestDynamicEndpointGroup endpointGroup = new TestDynamicEndpointGroup();
        EndpointGroupRegistry.register("dynamic", endpointGroup, strategy);
        endpointGroup.updateEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1000)));

        EndpointSelector selector = EndpointGroupRegistry.getNodeSelector("dynamic");
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1000));

        endpointGroup.updateEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111, 1),
                                                       Endpoint.of("127.0.0.1", 2222, 2)));

        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222, 2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222, 2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1111, 1));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222, 2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222, 2));
        assertThat(selector.select(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1111, 1));
    }

    private static final class TestDynamicEndpointGroup extends DynamicEndpointGroup {
        void updateEndpoints(List<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }
    }
}
