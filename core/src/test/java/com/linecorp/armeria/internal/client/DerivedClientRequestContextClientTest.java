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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DerivedClientRequestContextClientTest {

    private final Endpoint endpointA = Endpoint.of("a.com", 8080);
    private final Endpoint endpointB = Endpoint.of("b.com", 8080);
    private final Endpoint endpointC = Endpoint.of("c.com", 8080);
    private SettableEndpointGroup group;

    @BeforeEach
    void setUp() {
        group = new SettableEndpointGroup();
        group.add(endpointA);
        group.add(endpointB);
        group.add(endpointC);
    }

    @Test
    void shouldAcquireNewEventLoopForNewEndpoint() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext parent = new DefaultClientRequestContext(
                new SimpleMeterRegistry(), SessionProtocol.H2C, RequestId.random(), HttpMethod.GET,
                RequestTarget.forClient("/"), ClientOptions.of(), request, null, RequestOptions.of(), 0, 0);
        parent.init(group);
        assertThat(parent.endpoint()).isEqualTo(endpointA);
        final ClientRequestContext child =
                ClientUtil.newDerivedContext(parent, request, null, false);
        assertThat(child.endpoint()).isEqualTo(endpointB);
        assertThat(parent.endpoint()).isNotSameAs(child.endpoint());
        assertThat(parent.eventLoop().withoutContext()).isNotSameAs(child.eventLoop().withoutContext());
    }

    @Test
    void shouldAcquireSameEventLoopForSameEndpoint() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext parent = new DefaultClientRequestContext(
                new SimpleMeterRegistry(), SessionProtocol.H2C, RequestId.random(), HttpMethod.GET,
                RequestTarget.forClient("/"), ClientOptions.of(), request, null, RequestOptions.of(), 0, 0);
        parent.init(group);
        assertThat(parent.endpoint()).isEqualTo(endpointA);
        final ClientRequestContext childA0 =
                ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, true);
        assertThat(childA0.endpoint()).isEqualTo(endpointA);
        final ClientRequestContext childB0 =
                ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, false);
        assertThat(childB0.endpoint()).isEqualTo(endpointB);
        final ClientRequestContext childC0 =
                ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, false);
        assertThat(childC0.endpoint()).isEqualTo(endpointC);

        for (int i = 0; i < 3; i++) {
            final ClientRequestContext childA1 =
                    ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, false);
            assertThat(childA1.endpoint()).isEqualTo(endpointA);
            assertThat(childA1.eventLoop().withoutContext()).isSameAs(childA0.eventLoop().withoutContext());
            final ClientRequestContext childB1 =
                    ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, false);
            assertThat(childB1.endpoint()).isEqualTo(endpointB);
            assertThat(childB1.eventLoop().withoutContext()).isSameAs(childB0.eventLoop().withoutContext());
            final ClientRequestContext childC1 =
                    ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, false);
            assertThat(childC1.endpoint()).isEqualTo(endpointC);
            assertThat(childC1.eventLoop().withoutContext()).isSameAs(childC0.eventLoop().withoutContext());
        }
    }

    @Test
    void shouldNotAcquireNewEventLoopForInitialAttempt() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final DefaultClientRequestContext parent = new DefaultClientRequestContext(
                new SimpleMeterRegistry(), SessionProtocol.H2C, RequestId.random(), HttpMethod.GET,
                RequestTarget.forClient("/"), ClientOptions.of(), request, null, RequestOptions.of(), 0, 0);
        parent.init(group);
        assertThat(parent.endpoint()).isEqualTo(endpointA);
        final ClientRequestContext child =
                ClientUtil.newDerivedContext(parent, HttpRequest.of(HttpMethod.GET, "/"), null, true);
        assertThat(child.endpoint()).isEqualTo(endpointA);
        assertThat(parent.endpoint()).isSameAs(child.endpoint());
        assertThat(parent.eventLoop().withoutContext()).isSameAs(child.eventLoop().withoutContext());
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
