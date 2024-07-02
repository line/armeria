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

package com.linecorp.armeria.common.loadbalancer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

class StickyLoadBalancerTest {

    private static final String STICKY_HEADER_NAME = "USER_COOKIE";

    final ToLongFunction<ClientRequestContext> hasher = (ClientRequestContext ctx) -> {
        return ctx.request().headers()
                  .get(HttpHeaderNames.of(STICKY_HEADER_NAME))
                  .hashCode();
    };

    private final List<Endpoint> endpoints = ImmutableList.of(
            Endpoint.parse("localhost:1234"),
            Endpoint.parse("localhost:2345"),
            Endpoint.parse("localhost:3333"),
            Endpoint.parse("localhost:5555"),
            Endpoint.parse("localhost:3444"),
            Endpoint.parse("localhost:9999"),
            Endpoint.parse("localhost:1111")
    );

    @Test
    void select() {
        final LoadBalancer<Endpoint, ClientRequestContext> loadBalancer =
                LoadBalancer.ofSticky(endpoints, hasher);
        final int selectTime = 5;

        final Endpoint ep1 = loadBalancer.pick(contextWithHeader(STICKY_HEADER_NAME, "armeria1"));
        final Endpoint ep2 = loadBalancer.pick(contextWithHeader(STICKY_HEADER_NAME, "armeria2"));
        final Endpoint ep3 = loadBalancer.pick(contextWithHeader(STICKY_HEADER_NAME, "armeria3"));

        // select few times to confirm that same header will be routed to same endpoint
        for (int i = 0; i < selectTime; i++) {
            assertThat(loadBalancer.pick(contextWithHeader(STICKY_HEADER_NAME, "armeria1"))).isEqualTo(ep1);
            assertThat(loadBalancer.pick(contextWithHeader(STICKY_HEADER_NAME, "armeria2"))).isEqualTo(ep2);
            assertThat(loadBalancer.pick(contextWithHeader(STICKY_HEADER_NAME, "armeria3"))).isEqualTo(ep3);
        }

        final Endpoint ep4 = Endpoint.parse("localhost:9494");
        final List<Endpoint> newEndpoints = ImmutableList.of(ep4);

        final LoadBalancer<Endpoint, ClientRequestContext> loadBalancer1 =
                LoadBalancer.ofSticky(newEndpoints, hasher);
        assertThat(loadBalancer1.pick(contextWithHeader(STICKY_HEADER_NAME, "armeria1"))).isEqualTo(ep4);
    }

    private static ClientRequestContext contextWithHeader(String k, String v) {
        return ClientRequestContext.of(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                                        HttpHeaderNames.of(k), v)));
    }
}
