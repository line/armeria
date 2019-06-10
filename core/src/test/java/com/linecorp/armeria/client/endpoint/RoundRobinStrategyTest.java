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
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

public class RoundRobinStrategyTest {
    private static final EndpointGroup ENDPOINT_GROUP =
            new StaticEndpointGroup(Endpoint.parse("localhost:1234"),
                                    Endpoint.parse("localhost:2345"));

    private static final EndpointGroup EMPTY_ENDPOINT_GROUP = new StaticEndpointGroup();

    private final RoundRobinStrategy strategy = new RoundRobinStrategy();

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Before
    public void setup() {
        EndpointGroupRegistry.register("endpoint", ENDPOINT_GROUP, strategy);
        EndpointGroupRegistry.register("empty", EMPTY_ENDPOINT_GROUP, strategy);
    }

    @Test
    public void select() {
        assertThat(EndpointGroupRegistry.selectNode(ctx, "endpoint"))
                .isEqualTo(ENDPOINT_GROUP.endpoints().get(0));
        assertThat(EndpointGroupRegistry.selectNode(ctx, "endpoint"))
                .isEqualTo(ENDPOINT_GROUP.endpoints().get(1));
        assertThat(EndpointGroupRegistry.selectNode(ctx, "endpoint"))
                .isEqualTo(ENDPOINT_GROUP.endpoints().get(0));
        assertThat(EndpointGroupRegistry.selectNode(ctx, "endpoint"))
                .isEqualTo(ENDPOINT_GROUP.endpoints().get(1));
    }

    @Test
    public void select_empty() {
        assertThat(EndpointGroupRegistry.selectNode(ctx, "endpoint")).isNotNull();

        assertThat(catchThrowable(() -> EndpointGroupRegistry.selectNode(ctx, "empty")))
                .isInstanceOf(EndpointGroupException.class);
    }
}
