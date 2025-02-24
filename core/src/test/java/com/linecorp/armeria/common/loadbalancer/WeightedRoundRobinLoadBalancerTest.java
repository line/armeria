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

package com.linecorp.armeria.common.loadbalancer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

class WeightedRoundRobinLoadBalancerTest {

    @Test
    void select() {
        final SimpleLoadBalancer<Endpoint> loadBalancer =
                LoadBalancer.ofWeightedRoundRobin(
                        ImmutableList.of(Endpoint.parse("localhost:1234"),
                                         Endpoint.parse("localhost:2345")));
        assertThat(loadBalancer.pick()).isNotNull();
        assertThat(LoadBalancer.ofWeightedRoundRobin(ImmutableList.of()).pick()).isNull();
    }

    @Test
    void testRoundRobinSelect() {
        final SimpleLoadBalancer<Endpoint> loadBalancer =
                LoadBalancer.ofRoundRobin(
                        ImmutableList.of(
                                Endpoint.of("127.0.0.1", 1234),
                                Endpoint.of("127.0.0.1", 2345),
                                Endpoint.of("127.0.0.1", 3456)));

        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");
    }

    @Test
    void testWeightedRoundRobinSelect() {
        //weight 1,2,3
        final SimpleLoadBalancer<Endpoint> loadBalancer =
                LoadBalancer.ofWeightedRoundRobin(
                        ImmutableList.of(
                                Endpoint.of("127.0.0.1", 1234).withWeight(1),
                                Endpoint.of("127.0.0.1", 2345).withWeight(2),
                                Endpoint.of("127.0.0.1", 3456).withWeight(3)));

        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");

        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer.pick().authority()).isEqualTo("127.0.0.1:3456");

        //weight 3,2,2
        final SimpleLoadBalancer<Endpoint> loadBalancer2 =
                LoadBalancer.ofWeightedRoundRobin(
                        ImmutableList.of(
                                Endpoint.of("127.0.0.1", 1234).withWeight(3),
                                Endpoint.of("127.0.0.1", 2345).withWeight(2),
                                Endpoint.of("127.0.0.1", 3456).withWeight(2)), Endpoint::weight);

        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:1234");
        //new round
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer2.pick().authority()).isEqualTo("127.0.0.1:1234");

        //weight 4,4,4
        final SimpleLoadBalancer<Endpoint> loadBalancer3 =
                LoadBalancer.ofWeightedRoundRobin(
                        ImmutableList.of(
                                Endpoint.of("127.0.0.1", 1234).withWeight(4),
                                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                                Endpoint.of("127.0.0.1", 3456).withWeight(4)), Endpoint::weight);

        assertThat(loadBalancer3.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer3.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer3.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer3.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer3.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer3.pick().authority()).isEqualTo("127.0.0.1:3456");

        //weight 2,4,6
        final SimpleLoadBalancer<Endpoint> loadBalancer4 =
                LoadBalancer.ofWeightedRoundRobin(
                        ImmutableList.of(
                                Endpoint.of("127.0.0.1", 1234).withWeight(2),
                                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                                Endpoint.of("127.0.0.1", 3456).withWeight(6)), Endpoint::weight);

        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        //new round
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer4.pick().authority()).isEqualTo("127.0.0.1:3456");

        //weight 4,6,2
        final SimpleLoadBalancer<Endpoint> loadBalancer5 =
                LoadBalancer.ofWeightedRoundRobin(
                        ImmutableList.of(
                                Endpoint.of("127.0.0.1", 2345).withWeight(4),
                                Endpoint.of("127.0.0.1", 3456).withWeight(6),
                                Endpoint.of("127.0.0.1", 1234).withWeight(2)), Endpoint::weight);

        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        //new round
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:1234");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:2345");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");
        assertThat(loadBalancer5.pick().authority()).isEqualTo("127.0.0.1:3456");

        //weight dynamic with random weight
        final Random rnd = new Random();

        final int numberOfEndpoint = 500;
        final int[] weights = new int[numberOfEndpoint];

        final ImmutableList.Builder<Endpoint> endpointBuilder = ImmutableList.builder();
        long totalWeight = 0;
        for (int i = 0; i < numberOfEndpoint; i++) {
            weights[i] = i == 0 ? weights[i] : weights[i - 1] + rnd.nextInt(100);
            totalWeight += weights[i];
            endpointBuilder.add(Endpoint.of("127.0.0.1", i + 1).withWeight(weights[i]));
        }
        final SimpleLoadBalancer<Endpoint> dynamic = LoadBalancer.ofWeightedRoundRobin(endpointBuilder.build(),
                                                                                       Endpoint::weight);

        int chosen = 0;
        while (totalWeight-- > 0) {
            while (weights[chosen] == 0) {
                chosen = (chosen + 1) % numberOfEndpoint;
            }

            assertThat(dynamic.pick().authority()).isEqualTo("127.0.0.1:" + (chosen + 1));
            weights[chosen]--;

            chosen = (chosen + 1) % numberOfEndpoint;
        }
    }
}
