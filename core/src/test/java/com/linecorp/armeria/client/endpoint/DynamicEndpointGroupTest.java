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
package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

class DynamicEndpointGroupTest {

    @Test
    void updateEndpoints() {
        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final AtomicInteger updateListenerCalled = new AtomicInteger(0);
        endpointGroup.addListener(l -> updateListenerCalled.incrementAndGet());
        assertThat(updateListenerCalled.get()).isEqualTo(0);

        // Start with two endpoints.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 3333),
                                                    Endpoint.of("127.0.0.1", 1111)));
        assertThat(endpointGroup.endpoints()).containsExactly(Endpoint.of("127.0.0.1", 1111),
                                                              Endpoint.of("127.0.0.1", 3333));
        assertThat(updateListenerCalled.get()).isEqualTo(1);

        // Set the same two endpoints. Nothing should happen.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                    Endpoint.of("127.0.0.1", 3333)));
        assertThat(updateListenerCalled.get()).isEqualTo(1);

        // Make sure the order of endpoints does not matter. Nothing should happen.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 3333),
                                                    Endpoint.of("127.0.0.1", 1111)));
        assertThat(updateListenerCalled.get()).isEqualTo(1);

        // Add a new endpoint.
        endpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 2222));
        assertThat(updateListenerCalled.get()).isEqualTo(2);
        assertThat(endpointGroup.endpoints()).containsExactly(Endpoint.of("127.0.0.1", 1111),
                                                              Endpoint.of("127.0.0.1", 2222),
                                                              Endpoint.of("127.0.0.1", 3333));

        // Remove the added endpoint.
        endpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 2222));
        assertThat(updateListenerCalled.get()).isEqualTo(3);
        assertThat(endpointGroup.endpoints()).containsExactly(Endpoint.of("127.0.0.1", 1111),
                                                              Endpoint.of("127.0.0.1", 3333));

        // Update the weight of one of the endpoints.
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                    Endpoint.of("127.0.0.1", 3333).withWeight(500)));
        assertThat(updateListenerCalled.get()).isEqualTo(4);
        assertThat(endpointGroup.endpoints()).containsExactly(Endpoint.of("127.0.0.1", 1111),
                                                              Endpoint.of("127.0.0.1", 3333).withWeight(500));
    }

    @Test
    void whenReadyContainsEndpointGroupWhenTheListIsUsedForTheFirstTime() {
        final DynamicEndpointGroup endpointGroup1 = new DynamicEndpointGroup();
        endpointGroup1.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 3333),
                                                     Endpoint.of("127.0.0.1", 1111)));
        assertThat(endpointGroup1.whenReady().join())
                .containsExactlyInAnyOrder(Endpoint.of("127.0.0.1", 3333),
                                           Endpoint.of("127.0.0.1", 1111));
        // Add a new endpoint.
        endpointGroup1.addEndpoint(Endpoint.of("127.0.0.1", 2222));
        // The list from whenReady is not changed.
        assertThat(endpointGroup1.whenReady().join())
                .containsExactlyInAnyOrder(Endpoint.of("127.0.0.1", 3333),
                                           Endpoint.of("127.0.0.1", 1111));

        final DynamicEndpointGroup endpointGroup2 = new DynamicEndpointGroup();
        endpointGroup2.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 3333),
                                                     Endpoint.of("127.0.0.1", 1111)));
        endpointGroup2.addEndpoint(Endpoint.of("127.0.0.1", 2222));

        // whenReady contains every endpoints.
        assertThat(endpointGroup2.whenReady().join())
                .containsExactlyInAnyOrder(Endpoint.of("127.0.0.1", 3333),
                                           Endpoint.of("127.0.0.1", 1111),
                                           Endpoint.of("127.0.0.1", 2222));
    }
}
