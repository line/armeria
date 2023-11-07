/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;

class DerivedClientRequestContextClientTest {

    @Test
    void shouldAcquireNewEventLoopForNewEndpoint() {
        final Endpoint endpointA = Endpoint.of("a.com", 8080);
        final Endpoint endpointB = Endpoint.of("a.com", 8080);
        final SettableEndpointGroup group = new SettableEndpointGroup();
        group.add(endpointA);
        group.add(endpointB);
        final ClientRequestContext parent = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                                .endpointGroup(group)
                                                                .eventLoop(ImmediateEventLoop.INSTANCE)
                                                                .build();
        assertThat(parent.endpoint()).isEqualTo(endpointA);
        final ClientRequestContext child =
                ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, false);
        assertThat(child.endpoint()).isEqualTo(endpointB);
        assertThat(parent.endpoint()).isNotSameAs(child.endpoint());
    }

    private static class SettableEndpointGroup extends DynamicEndpointGroup {

        SettableEndpointGroup() {
            super(EndpointSelectionStrategy.roundRobin());
        }

        void add(Endpoint endpoint) {
            addEndpoint(endpoint);
        }
    }
}
