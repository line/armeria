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

package com.linecorp.armeria.common.loadbalancer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

class RoundRobinLoadBalancerTest {

    @Test
    void pick() {
        final ImmutableList<Endpoint> endpoints = ImmutableList.of(Endpoint.parse("localhost:1234"),
                                                                   Endpoint.parse("localhost:2345"));
        final SimpleLoadBalancer<Endpoint> loadBalancer = LoadBalancer.ofRoundRobin(endpoints);
        assertThat(loadBalancer.pick()).isEqualTo(endpoints.get(0));
        assertThat(loadBalancer.pick()).isEqualTo(endpoints.get(1));
        assertThat(loadBalancer.pick()).isEqualTo(endpoints.get(0));
        assertThat(loadBalancer.pick()).isEqualTo(endpoints.get(1));
    }

    @Test
    void pickEmpty() {
        final SimpleLoadBalancer<Endpoint> loadBalancer = LoadBalancer.ofRoundRobin(ImmutableList.of());
        assertThat(loadBalancer.pick()).isNull();
    }
}
