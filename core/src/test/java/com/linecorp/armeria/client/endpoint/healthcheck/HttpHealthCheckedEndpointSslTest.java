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

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

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

    /**
     * TODO: placeholder exception -- to decide exact behavior after further analysis
     */
    public static class SomeSslException extends RuntimeException {
        private static final long serialVersionUID = -6543250369255632036L;
    }

    @Test
    void plainTextRequstToHttpsEndpoint() throws Exception {
        server.start();
        final int port = server.httpsPort();
        final WebClient webClient = WebClient.builder(SessionProtocol.HTTP, Endpoint.of("localhost", port))
                                             .decorator(LoggingClient.newDecorator()).build();
        assertThrows(SomeSslException.class,
                     () -> webClient.get(HEALTH_CHECK_PATH).aggregate().get(10, TimeUnit.SECONDS));
    }

    @Test
    void sslRequstToPlainTextEndpoint() throws Exception {
        server.start();
        final int port = server.httpPort();
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .sslContextCustomizer(
                                     s -> s.trustManager(InsecureTrustManagerFactory.INSTANCE))
                             .build();
        final WebClient webClient = WebClient.builder(SessionProtocol.HTTPS, Endpoint.of("localhost", port))
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator()).build();
        assertThrows(SomeSslException.class,
                     () -> webClient.get(HEALTH_CHECK_PATH).aggregate().get(10, TimeUnit.SECONDS));
    }
}
