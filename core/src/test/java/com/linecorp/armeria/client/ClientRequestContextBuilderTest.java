/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class ClientRequestContextBuilderTest {

    @Test
    void testTimeout() {
        final ClientRequestContext ctx1 = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                              .timedOut(true)
                                                              .build();
        assertThat(ctx1.isTimedOut()).isTrue();

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(ctx2.isTimedOut()).isFalse();
    }

    @Test
    void testEndPointGroup() {
        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(ctx1.endpoint()).isEqualTo(EndpointGroup.of(
                Endpoint.of("127.0.0.1")));

        final ClientRequestContext ctx2 = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                              .endpointGroup(EndpointGroup.of(
                                                                      Endpoint.of("127.0.0.1", 1)))
                                                              .build();
        assertThat(ctx2.endpoint()).isEqualTo(EndpointGroup.of(
                Endpoint.of("127.0.0.1", 1)));
    }
}
