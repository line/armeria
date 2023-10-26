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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void shouldNotifyNestedListener(boolean notifyLatestValue) {
        final DynamicEndpointGroup group = new DynamicEndpointGroup();
        final AtomicInteger invoked = new AtomicInteger();
        group.addListener(unused0 -> {
            invoked.incrementAndGet();
            // Add a listener while notifying the listeners.
            group.addListener(unused1 -> invoked.incrementAndGet(), true);
        }, notifyLatestValue);

        group.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111)));
        assertThat(invoked).hasValue(2);
    }

    @Test
    void removeDuringNotification() {
        final DynamicEndpointGroup group = new DynamicEndpointGroup();
        group.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111)));

        final AtomicInteger invoked = new AtomicInteger();
        class RemovableListener implements Consumer<List<Endpoint>> {
            @Override
            public void accept(List<Endpoint> endpoints) {
                invoked.incrementAndGet();
                group.removeListener(this);
            }
        }

        group.addListener(new RemovableListener(), true);
        assertThat(invoked).hasValue(1);
        group.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 2222)));
        // Make sure the listener is correctly removed.
        assertThat(invoked).hasValue(1);
    }

    @Test
    void removeEndpointWhenAllowEmptyEndpointsIsTrue() {
        final Endpoint endpoint1 = Endpoint.of("127.0.0.1", 1111);
        final Endpoint endpoint2 = Endpoint.of("127.0.0.1", 2222);
        final Iterable<Endpoint> endpoints = ImmutableList.of(endpoint1, endpoint2);
        final DynamicEndpointGroup dynamicEndpointGroup = new DynamicEndpointGroup();
        dynamicEndpointGroup.setEndpoints(endpoints);
        dynamicEndpointGroup.removeEndpoint(endpoint1);
        assertThat(dynamicEndpointGroup.endpoints()).containsExactlyInAnyOrder(endpoint2);

        dynamicEndpointGroup.removeEndpoint(endpoint2);
        assertThat(dynamicEndpointGroup.endpoints()).isEmpty();
    }

    @Test
    void removeEndpointWhenAllowEmptyEndpointsIsFalse() {
        final Endpoint endpoint1 = Endpoint.of("127.0.0.1", 1111);
        final Endpoint endpoint2 = Endpoint.of("127.0.0.1", 2222);
        final Iterable<Endpoint> endpoints = ImmutableList.of(endpoint1, endpoint2);
        final DynamicEndpointGroup dynamicEndpointGroup = new DynamicEndpointGroup(false);
        dynamicEndpointGroup.setEndpoints(endpoints);
        dynamicEndpointGroup.removeEndpoint(endpoint1);
        assertThat(dynamicEndpointGroup.endpoints()).containsExactlyInAnyOrder(endpoint2);

        // Shouldn't remove the last endpoint when allowEmptyEndpoints is false.
        dynamicEndpointGroup.removeEndpoint(endpoint2);
        assertThat(dynamicEndpointGroup.endpoints()).isNotEmpty();
        assertThat(dynamicEndpointGroup.endpoints()).containsExactlyInAnyOrder(endpoint2);
    }

    @Test
    void whenAllowEmptyEndpointsIsTrueByDefault() {
        final Iterable<Endpoint> endpoints = ImmutableList.of(Endpoint.of("127.0.0.1", 3333),
                                                              Endpoint.of("127.0.0.1", 1111));
        final DynamicEndpointGroup dynamicEndpointGroup = new DynamicEndpointGroup();
        testWhenAllowEmptyEndpointsIsTrue(dynamicEndpointGroup, endpoints);

        // Using builder
        final DynamicEndpointGroup dynamicEndpointGroupFromBuilder =
                DynamicEndpointGroup.builder().build();
        testWhenAllowEmptyEndpointsIsTrue(dynamicEndpointGroupFromBuilder, endpoints);
    }

    @Test
    void whenAllowEmptyEndpointsIsFalse() {
        final Iterable<Endpoint> endpoints = ImmutableList.of(Endpoint.of("127.0.0.1", 3333),
                                                              Endpoint.of("127.0.0.1", 1111));
        final DynamicEndpointGroup dynamicEndpointGroup = new DynamicEndpointGroup(false);
        testWhenAllowEmptyEndpointsIsFalse(dynamicEndpointGroup, endpoints);

        // Using builder
        final DynamicEndpointGroup dynamicEndpointGroupFromBuilder =
                DynamicEndpointGroup.builder()
                                    .allowEmptyEndpoints(false)
                                    .build();

        testWhenAllowEmptyEndpointsIsFalse(dynamicEndpointGroupFromBuilder, endpoints);
    }

    private static void testWhenAllowEmptyEndpointsIsTrue(DynamicEndpointGroup dynamicEndpointGroup,
                                                          Iterable<Endpoint> endpoints) {
        dynamicEndpointGroup.setEndpoints(endpoints);
        assertThat(dynamicEndpointGroup.endpoints()).containsExactlyInAnyOrderElementsOf(endpoints);

        // Should be allowed to set an empty list.
        dynamicEndpointGroup.setEndpoints(new ArrayList<>());
        assertThat(dynamicEndpointGroup.endpoints()).isEmpty();
    }

    private static void testWhenAllowEmptyEndpointsIsFalse(DynamicEndpointGroup dynamicEndpointGroup,
                                                           Iterable<Endpoint> endpoints) {
        dynamicEndpointGroup.setEndpoints(endpoints);
        assertThat(dynamicEndpointGroup.endpoints()).containsExactlyInAnyOrderElementsOf(endpoints);

        // Should not allow any attempt to set an empty list.
        dynamicEndpointGroup.setEndpoints(ImmutableList.of());
        assertThat(dynamicEndpointGroup.endpoints()).containsExactlyInAnyOrderElementsOf(endpoints);
        assertThat(dynamicEndpointGroup.endpoints()).isNotEmpty();
    }
}
