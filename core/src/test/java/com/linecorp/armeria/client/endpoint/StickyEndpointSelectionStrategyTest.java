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

import java.util.function.ToLongFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

class StickyEndpointSelectionStrategyTest {

    private static final String STICKY_HEADER_NAME = "USER_COOKIE";

    final ToLongFunction<ClientRequestContext> hasher = (ClientRequestContext ctx) -> {
        return ctx.request().headers()
                  .get(HttpHeaderNames.of(STICKY_HEADER_NAME))
                  .hashCode();
    };

    final StickyEndpointSelectionStrategy strategy = new StickyEndpointSelectionStrategy(hasher);

    private final EndpointGroup staticGroup = EndpointGroup.of(
            strategy,
            Endpoint.parse("localhost:1234"),
            Endpoint.parse("localhost:2345"),
            Endpoint.parse("localhost:3333"),
            Endpoint.parse("localhost:5555"),
            Endpoint.parse("localhost:3444"),
            Endpoint.parse("localhost:9999"),
            Endpoint.parse("localhost:1111")
    );

    private final DynamicEndpointGroup dynamicGroup = new DynamicEndpointGroup(strategy);

    @BeforeEach
    void setUp() {
        dynamicGroup.setEndpoints(ImmutableList.of());
    }

    @Test
    void select() {
        assertThat(strategy.newSelector(staticGroup)).isNotNull();
        final int selectTime = 5;

        final Endpoint ep1 = staticGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria1"));
        final Endpoint ep2 = staticGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria2"));
        final Endpoint ep3 = staticGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria3"));

        // select few times to confirm that same header will be routed to same endpoint
        for (int i = 0; i < selectTime; i++) {
            assertThat(staticGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria1"))).isEqualTo(ep1);
            assertThat(staticGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria2"))).isEqualTo(ep2);
            assertThat(staticGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria3"))).isEqualTo(ep3);
        }

        //confirm rebuild tree of dynamic
        final Endpoint ep4 = Endpoint.parse("localhost:9494");
        dynamicGroup.addEndpoint(ep4);
        assertThat(dynamicGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria1"))).isEqualTo(ep4);

        dynamicGroup.removeEndpoint(ep4);
        assertThat(dynamicGroup.selectNow(contextWithHeader(STICKY_HEADER_NAME, "armeria1"))).isNull();
    }

    private static ClientRequestContext contextWithHeader(String k, String v) {
        return ClientRequestContext.of(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                                        HttpHeaderNames.of(k), v)));
    }
}
