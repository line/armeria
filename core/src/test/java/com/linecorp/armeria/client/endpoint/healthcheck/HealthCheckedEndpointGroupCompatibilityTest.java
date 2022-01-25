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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HealthCheckedEndpointGroupCompatibilityTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/no_value",
                       (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/bad_value",
                       (ctx, req) -> HttpResponse.of(ResponseHeaders.of(
                               HttpStatus.OK, "armeria-lphc", "bad_value")));
            sb.service("/0.97",
                       (ctx, req) -> HttpResponse.of(ResponseHeaders.of(
                               HttpStatus.OK, "armeria-lphc", 60)));
        }
    };

    @Test
    void compatibilityWithUnsupportedServer() throws Exception {
        test("/no_value");
    }

    @Test
    void compatibilityWithBadServer() throws Exception {
        test("/bad_value");
    }

    @Test
    void compatibilityWith0_97() throws Exception {
        test("/0.97");
    }

    private static void test(String path) {
        final Endpoint endpoint = Endpoint.of("127.0.0.1", server.httpPort());
        try (HealthCheckedEndpointGroup endpointGroup =
                     HealthCheckedEndpointGroup.of(endpoint, path)) {
            endpointGroup.whenReady().join();
            // Check the initial state (healthy).
            assertThat(endpointGroup.endpoints()).containsExactly(endpoint);
        }
    }
}
