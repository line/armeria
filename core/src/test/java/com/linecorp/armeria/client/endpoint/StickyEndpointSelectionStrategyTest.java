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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.ToLongFunction;

import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpRequest;

import io.netty.util.AsciiString;

public class StickyEndpointSelectionStrategyTest {

    private static final String STICKY_HEADER_NAME = "USER_COOKIE";

    ToLongFunction<ClientRequestContext> hasher = (ClientRequestContext ctx) -> {
            return ((HttpRequest) ctx.request()).headers()
                                                .get(AsciiString.of(STICKY_HEADER_NAME))
                                                .hashCode();
    };
    final StickyEndpointSelectionStrategy strategy = new StickyEndpointSelectionStrategy(hasher);

    private static final EndpointGroup STATIC_ENDPOINT_GROUP = new StaticEndpointGroup(
            Endpoint.of("localhost:1234"),
            Endpoint.of("localhost:2345"),
            Endpoint.of("localhost:3333"),
            Endpoint.of("localhost:5555"),
            Endpoint.of("localhost:3444"),
            Endpoint.of("localhost:9999"),
            Endpoint.of("localhost:1111")
    );

    private static final DynamicEndpointGroup DYNAMIC_ENDPOINT_GROUP = new DynamicEndpointGroup();

    @Before
    public void setup() {
        EndpointGroupRegistry.register("static", STATIC_ENDPOINT_GROUP, strategy);
        EndpointGroupRegistry.register("dynamic", DYNAMIC_ENDPOINT_GROUP, strategy);
    }

    @Test
    public void select() {
        final EndpointSelector selector = strategy.newSelector(STATIC_ENDPOINT_GROUP);
        final int selectTime = 5;

        Endpoint ep1 = EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria1"), "static");
        Endpoint ep2 = EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria2"), "static");
        Endpoint ep3 = EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria3"), "static");

        // select few times to confirm that same header will be routed to same endpoint
        for (int i = 0; i < selectTime; i++) {
            assertThat(EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria1"), "static")).isEqualTo(ep1);
            assertThat(EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria2"), "static")).isEqualTo(ep2);
            assertThat(EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria3"), "static")).isEqualTo(ep3);
        }

        //confirm rebuild tree of dynamic
        Endpoint ep4 = Endpoint.of("localhost:9494");
        DYNAMIC_ENDPOINT_GROUP.addEndpoint(ep4);
        assertThat(EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria1"), "dynamic")).isEqualTo(ep4);

        DYNAMIC_ENDPOINT_GROUP.removeEndpoint(ep4);
        assertThatThrownBy(
                () -> EndpointGroupRegistry.selectNode(
                        contextWithHeader(STICKY_HEADER_NAME, "armeria1"), "dynamic")
        ).isInstanceOf(EndpointGroupException.class);
    }

    private static ClientRequestContext contextWithHeader(String k, String v) {
        ClientRequestContext ctx = mock(ClientRequestContext.class);
        HttpRequest testReq = new DefaultHttpRequest();
        testReq.headers().set(AsciiString.of(k), v);
        when(ctx.request()).thenReturn(testReq);
        return ctx;
    }
}
