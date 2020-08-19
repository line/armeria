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

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.roundRobin;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class RoundRobinStrategyTest {
    private static final EndpointGroup group =
            EndpointGroup.of(roundRobin(),
                             Endpoint.parse("localhost:1234"),
                             Endpoint.parse("localhost:2345"));

    private static final EndpointGroup emptyGroup = EndpointGroup.of();

    private final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void select() {
        assertThat(group.selectNow(ctx))
                .isEqualTo(group.endpoints().get(0));
        assertThat(group.selectNow(ctx))
                .isEqualTo(group.endpoints().get(1));
        assertThat(group.selectNow(ctx))
                .isEqualTo(group.endpoints().get(0));
        assertThat(group.selectNow(ctx))
                .isEqualTo(group.endpoints().get(1));
    }

    @Test
    void selectEmpty() {
        assertThat(group.selectNow(ctx)).isNotNull();
        assertThat(emptyGroup.selectNow(ctx)).isNull();
    }
}
