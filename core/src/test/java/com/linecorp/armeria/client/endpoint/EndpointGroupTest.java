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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

class EndpointGroupTest {

    private static final Endpoint FOO = Endpoint.of("foo");
    private static final Endpoint BAR = Endpoint.of("bar");
    private static final Endpoint CAT = Endpoint.of("cat");
    private static final Endpoint DOG = Endpoint.of("dog");
    private static final Endpoint HELLO = Endpoint.of("hello");
    private static final Endpoint WORLD = Endpoint.of("world");
    private static final Endpoint GITHUB = Endpoint.of("github");

    @Test
    void orElse() {
        final EndpointGroup emptyEndpointGroup = EndpointGroup.of();
        final EndpointGroup endpointGroup1 = EndpointGroup.of(Endpoint.of("127.0.0.1", 1234));
        // Make sure factory that takes an Iterable accepts a list of Endpoint (subclass).
        final List<Endpoint> endpoint2Endpoints = ImmutableList.of(Endpoint.of("127.0.0.1", 2345));
        final EndpointGroup endpointGroup2 = EndpointGroup.of(endpoint2Endpoints);

        assertThat(emptyEndpointGroup.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup2.endpoints());
        assertThat(endpointGroup1.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup1.endpoints());
    }

    @Test
    void normal() {
        final DynamicEndpointGroup group1 = new DynamicEndpointGroup();
        group1.setEndpoints(ImmutableList.of(FOO, BAR));
        final DynamicEndpointGroup group2 = new DynamicEndpointGroup();
        group2.setEndpoints(ImmutableList.of(CAT, DOG));
        final EndpointGroup group3 = EndpointGroup.of(HELLO, WORLD);

        final EndpointGroup composite = EndpointGroup.of(group1, group2, group3, GITHUB);
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, BAR, CAT, DOG, HELLO, WORLD, GITHUB);
        // Same instance of endpoints returned unless there are updates.
        assertThat(composite.endpoints()).isSameAs(composite.endpoints());

        group1.setEndpoints(ImmutableList.of(FOO));
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, CAT, DOG, HELLO, WORLD, GITHUB);

        group1.setEndpoints(ImmutableList.of(FOO, BAR));
        group2.setEndpoints(ImmutableList.of());
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, BAR, HELLO, WORLD, GITHUB);
    }

    @Test
    void oneEndpoint() {
        final EndpointGroup composite = EndpointGroup.of(GITHUB);
        assertThat(composite.endpoints()).containsExactly(GITHUB);
        assertThat(composite).isSameAs(GITHUB);
    }

    @Test
    void oneGroup() {
        final EndpointGroup group = EndpointGroup.of(FOO, BAR);
        final EndpointGroup composite = EndpointGroup.of(group);
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, BAR);
    }
}
