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

import java.util.function.ToLongFunction;

import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

public class StickyEndpointSelectionStrategyTest {

    private static final String STICKY_HEADER_NAME = "USER_COOKIE";

    final ToLongFunction<ClientRequestContext> hasher = (ClientRequestContext ctx) -> {
        return ((HttpRequest) ctx.request()).headers()
                                            .get(HttpHeaderNames.of(STICKY_HEADER_NAME))
                                            .hashCode();
    };

    final StickyEndpointSelectionStrategy strategy = new StickyEndpointSelectionStrategy(hasher);

    private static final EndpointGroup STATIC_ENDPOINT_GROUP = new StaticEndpointGroup(
            Endpoint.parse("localhost:1234"),
            Endpoint.parse("localhost:2345"),
            Endpoint.parse("localhost:3333"),
            Endpoint.parse("localhost:5555"),
            Endpoint.parse("localhost:3444"),
            Endpoint.parse("localhost:9999"),
            Endpoint.parse("localhost:1111")
    );

    private static final DynamicEndpointGroup DYNAMIC_ENDPOINT_GROUP = new DynamicEndpointGroup();

    @Before
    public void setup() {
        EndpointGroupRegistry.register("static", STATIC_ENDPOINT_GROUP, strategy);
        EndpointGroupRegistry.register("dynamic", DYNAMIC_ENDPOINT_GROUP, strategy);
    }

    @Test
    public void select() {
        assertThat(strategy.newSelector(STATIC_ENDPOINT_GROUP)).isNotNull();
        final int selectTime = 5;

        final Endpoint ep1 = EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria1"), "static");
        final Endpoint ep2 = EndpointGroupRegistry.selectNode(
                contextWithHeader(STICKY_HEADER_NAME, "armeria2"), "static");
        final Endpoint ep3 = EndpointGroupRegistry.selectNode(
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
        final Endpoint ep4 = Endpoint.parse("localhost:9494");
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
        return ClientRequestContext.of(HttpRequest.of(HttpHeaders.of(HttpMethod.GET, "/")
                                                                 .set(HttpHeaderNames.of(k), v)));
    }
}
