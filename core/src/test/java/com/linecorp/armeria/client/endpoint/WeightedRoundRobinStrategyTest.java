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

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.roundRobin;
import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.weightedRoundRobin;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class WeightedRoundRobinStrategyTest {

    private static final EndpointGroup emptyGroup = EndpointGroup.of();

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void select() {
        assertThat(EndpointGroup.of(Endpoint.parse("localhost:1234"),
                                    Endpoint.parse("localhost:2345"))
                                .selectNow(ctx)).isNotNull();

        assertThat(emptyGroup.selectNow(ctx)).isNull();
    }

    @Test
    void testRoundRobinSelect() {
        final EndpointGroup group = EndpointGroup.of(
                roundRobin(),
                Endpoint.of("127.0.0.1", 1234),
                Endpoint.of("127.0.0.1", 2345),
                Endpoint.of("127.0.0.1", 3456));

        assertThat(group.selectionStrategy()).isSameAs(roundRobin());

        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
    }

    @Test
    void testWeightedRoundRobinSelect() {
        //weight 1,2,3
        final EndpointGroup group = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(1),
                Endpoint.of("127.0.0.1", 2345).withWeight(2),
                Endpoint.of("127.0.0.1", 3456).withWeight(3));

        assertThat(group.selectionStrategy()).isSameAs(weightedRoundRobin());

        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");

        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");

        //weight 3,2,2
        final EndpointGroup group2 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(3),
                Endpoint.of("127.0.0.1", 2345).withWeight(2),
                Endpoint.of("127.0.0.1", 3456).withWeight(2));

        assertThat(group2.selectionStrategy()).isSameAs(weightedRoundRobin());

        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        //new round
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group2.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");

        //weight 4,4,4
        final EndpointGroup group3 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(4),
                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                Endpoint.of("127.0.0.1", 3456).withWeight(4));

        assertThat(group3.selectionStrategy()).isSameAs(weightedRoundRobin());

        assertThat(group3.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group3.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group3.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group3.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group3.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group3.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");

        //weight 2,4,6
        final EndpointGroup group4 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1234).withWeight(2),
                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                Endpoint.of("127.0.0.1", 3456).withWeight(6));

        assertThat(group4.selectionStrategy()).isSameAs(weightedRoundRobin());

        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        //new round
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group4.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");

        //weight 4,6,2
        final EndpointGroup group5 = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                Endpoint.of("127.0.0.1", 3456).withWeight(6),
                Endpoint.of("127.0.0.1", 1234).withWeight(2));

        assertThat(group5.selectionStrategy()).isSameAs(weightedRoundRobin());

        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        //new round
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:1234");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:2345");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");
        assertThat(group5.selectNow(ctx).authority()).isEqualTo("127.0.0.1:3456");

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

        int chosen = 0;
        while (totalWeight-- > 0) {
            while (weights[chosen] == 0) {
                chosen = (chosen + 1) % numberOfEndpoint;
            }

            assertThat(dynamic.selectNow(ctx).authority()).isEqualTo("127.0.0.1:" + (chosen + 1));
            weights[chosen]--;

            chosen = (chosen + 1) % numberOfEndpoint;
        }
    }

    @Test
    void selectFromDynamicEndpointGroup() {
        final TestDynamicEndpointGroup group = new TestDynamicEndpointGroup();

        group.updateEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1000)));

        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1000));

        group.updateEndpoints(ImmutableList.of(
                Endpoint.of("127.0.0.1", 1111).withWeight(1),
                Endpoint.of("127.0.0.1", 2222).withWeight(2))
        );

        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1111).withWeight(1));
        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 2222).withWeight(2));
        assertThat(group.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 1111).withWeight(1));
    }

    private static final class TestDynamicEndpointGroup extends DynamicEndpointGroup {
        void updateEndpoints(List<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }
    }
}
