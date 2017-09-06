/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

public class OrElseEndpointGroupTest {
    @Test
    public void updateFirstEndpoints() {
        DynamicEndpointGroup firstEndpointGroup = new DynamicEndpointGroup();
        DynamicEndpointGroup secondEndpointGroup = new DynamicEndpointGroup();
        EndpointGroup endpointGroup = new OrElseEndpointGroup(firstEndpointGroup, secondEndpointGroup);

        firstEndpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                         Endpoint.of("127.0.0.1", 2222)));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 2222)));

        firstEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 3333));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 2222),
                                                                         Endpoint.of("127.0.0.1", 3333)));

        firstEndpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 2222));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 3333)));
    }

    @Test
    public void updateSecondEndpoints() {
        DynamicEndpointGroup firstEndpointGroup = new DynamicEndpointGroup();
        DynamicEndpointGroup secondEndpointGroup = new DynamicEndpointGroup();
        EndpointGroup endpointGroup = new OrElseEndpointGroup(firstEndpointGroup, secondEndpointGroup);

        secondEndpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                          Endpoint.of("127.0.0.1", 2222)));

        secondEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 3333));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 2222),
                                                                         Endpoint.of("127.0.0.1", 3333)));

        secondEndpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 2222));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 3333)));

        firstEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 4444));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 4444)));

        // Use firstEndpointGroup's endpoint list even if secondEndpointGroup has change.
        secondEndpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 5555));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 4444)));

        // Fallback to secondEndpointGroup if firstEndpointGroup has no endpoints.
        firstEndpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 4444));
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 3333),
                                                                         Endpoint.of("127.0.0.1", 5555)));
    }
}
