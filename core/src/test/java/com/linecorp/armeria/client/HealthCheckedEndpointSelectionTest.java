/*
 * Copyright 2022 LINE Corporation
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

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HealthCheckedEndpointSelectionTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(18080);
            sb.http(18081);
            sb.http(18082);
            sb.http(18083);
            sb.http(18084);
            sb.service("/health",
                       (ctx, req) -> HttpResponse.delayed(HttpResponse.of("OK"), Duration.ofMillis(100)));
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @Test
    void test() {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", 18080),
                                                     Endpoint.of("127.0.0.1", 18081),
                                                     Endpoint.of("127.0.0.1", 18082),
                                                     Endpoint.of("127.0.0.1", 18083),
                                                     Endpoint.of("127.0.0.1", 18084));
        final HealthCheckedEndpointGroup checkedEndpointGroup = HealthCheckedEndpointGroup.of(group, "/health");
        final BlockingWebClient client = WebClient.of(SessionProtocol.HTTP, checkedEndpointGroup).blocking();
        assertThat(client.get("/").contentUtf8()).isEqualTo("OK");
    }
}
