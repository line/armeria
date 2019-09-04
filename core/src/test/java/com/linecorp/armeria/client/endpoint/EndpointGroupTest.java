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
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

class EndpointGroupTest {

    private static final Endpoint FOO = Endpoint.of("foo");
    private static final Endpoint BAR = Endpoint.of("bar");
    private static final Endpoint CAT = Endpoint.of("cat");
    private static final Endpoint DOG = Endpoint.of("dog");

    @Test
    void orElse() {
        final EndpointGroup emptyEndpointGroup = EndpointGroup.empty();
        final EndpointGroup endpointGroup1 = EndpointGroup.of(Endpoint.of("127.0.0.1", 1234));
        final EndpointGroup endpointGroup2 = EndpointGroup.of(Endpoint.of("127.0.0.1", 2345));

        assertThat(emptyEndpointGroup.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup2.endpoints());
        assertThat(endpointGroup1.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup1.endpoints());
    }

    @Test
    void normal() {
        DynamicEndpointGroup group1 = new DynamicEndpointGroup();
        group1.setEndpoints(ImmutableList.of(FOO, BAR));
        DynamicEndpointGroup group2 = new DynamicEndpointGroup();
        group2.setEndpoints(ImmutableList.of(CAT, DOG));

        EndpointGroup composite = EndpointGroup.of(group1, group2);
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, BAR, CAT, DOG);
        // Same instance of endpoints returned unless there are updates.
        assertThat(composite.endpoints()).isSameAs(composite.endpoints());

        group1.setEndpoints(ImmutableList.of(FOO));
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, CAT, DOG);

        group1.setEndpoints(ImmutableList.of(FOO, BAR));
        group2.setEndpoints(ImmutableList.of());
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, BAR);
    }

    @Nested
    class InitialEndpoints {
        @Test
        void group1First() throws Exception {
            DynamicEndpointGroup group1 = new DynamicEndpointGroup();
            DynamicEndpointGroup group2 = new DynamicEndpointGroup();
            EndpointGroup composite = EndpointGroup.of(group1, group2);
            CompletableFuture<List<Endpoint>> initialEndpoints = composite.initialEndpointsFuture();
            assertThat(initialEndpoints).isNotCompleted();

            group1.setEndpoints(ImmutableList.of(FOO, BAR));
            group2.setEndpoints(ImmutableList.of(CAT, DOG));
            assertThat(initialEndpoints).isCompletedWithValue(ImmutableList.of(FOO, BAR));
            assertThat(composite.awaitInitialEndpoints()).containsExactly(FOO, BAR);
        }

        @Test
        void group2First() throws Exception {
            DynamicEndpointGroup group1 = new DynamicEndpointGroup();
            DynamicEndpointGroup group2 = new DynamicEndpointGroup();
            EndpointGroup composite = EndpointGroup.of(group1, group2);
            CompletableFuture<List<Endpoint>> initialEndpoints = composite.initialEndpointsFuture();
            assertThat(initialEndpoints).isNotCompleted();

            group1.setEndpoints(ImmutableList.of(FOO, BAR));
            group2.setEndpoints(ImmutableList.of(CAT, DOG));
            assertThat(initialEndpoints).isCompletedWithValue(ImmutableList.of(CAT, DOG));
            assertThat(composite.awaitInitialEndpoints()).containsExactly(CAT, DOG);
        }
    }
}
