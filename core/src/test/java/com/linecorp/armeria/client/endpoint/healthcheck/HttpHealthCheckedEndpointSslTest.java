/*
 * Copyright 2016 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.SessionProtocolNegotiationException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpHealthCheckedEndpointSslTest {

    private static final String HEALTH_CHECK_PATH = "/healthcheck";

    private static class HealthCheckServerExtension extends ServerExtension {

        HealthCheckServerExtension() {
            super(false); // Disable auto-start.
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service(HEALTH_CHECK_PATH, HealthCheckService.builder().longPolling(0).build());
        }
    }

    @RegisterExtension
    static final ServerExtension server = new HealthCheckServerExtension();

    @Test
    void plainTextRequstToHttpsEndpoint() throws Exception {
        server.start();
        final int port = server.httpsPort();
        final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, Endpoint.of("localhost", port))
                                             .build();
        assertThatThrownBy(() -> webClient.get(HEALTH_CHECK_PATH).aggregate().get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(SessionProtocolNegotiationException.class);
    }
}
