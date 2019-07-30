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

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

class CompositeEndpointGroupTest {

    private static final Endpoint FOO = Endpoint.of("foo");
    private static final Endpoint BAR = Endpoint.of("bar");
    private static final Endpoint CAT = Endpoint.of("cat");
    private static final Endpoint DOG = Endpoint.of("dog");

    @Test
    void normal() {
        DynamicEndpointGroup group1 = new DynamicEndpointGroup();
        group1.setEndpoints(ImmutableList.of(FOO, BAR));
        DynamicEndpointGroup group2 = new DynamicEndpointGroup();
        group2.setEndpoints(ImmutableList.of(CAT, DOG));

        CompositeEndpointGroup composite = new CompositeEndpointGroup(group1, group2);
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, BAR, CAT, DOG);
        // Same instance of endpoints returned unless there are updates.
        assertThat(composite.endpoints()).isSameAs(composite.endpoints());

        group1.setEndpoints(ImmutableList.of(FOO));
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, CAT, DOG);

        group1.setEndpoints(ImmutableList.of(FOO, BAR));
        group2.setEndpoints(ImmutableList.of());
        assertThat(composite.endpoints()).containsExactlyInAnyOrder(FOO, BAR);
    }
}
