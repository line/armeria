/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.USER_ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.athenz.AthenzExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AsciiString;

@EnabledIfDockerAvailable
class AthenzClientTest {

    private static final AtomicReference<String> capturedHeaderName = new AtomicReference<>();
    private static final AtomicReference<String> capturedHeaderValue = new AtomicReference<>();

    // AthenzExtension must be started before ServerExtension to ensure ZTS server is ready
    @Order(1)
    @RegisterExtension
    static final AthenzExtension athenzExtension = new AthenzExtension();

    // Mock server to capture and verify HTTP headers sent by AthenzClient
    @Order(2)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/api", (ctx, req) -> {
                // Capture the first matching header
                req.headers().forEach(entry -> {
                    final String headerName = entry.getKey().toString();
                    if ("X-Company-Token".equalsIgnoreCase(headerName) ||
                        "Authorization".equalsIgnoreCase(headerName)) {
                        capturedHeaderName.set(headerName);
                        capturedHeaderValue.set(entry.getValue());
                    }
                });
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @BeforeEach
    void setUp() {
        capturedHeaderName.set(null);
        capturedHeaderValue.set(null);
    }

    @Test
    void customHeaderIsUsedInRequest() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient("foo-service")) {
            final CustomAthenzHeader customHeader = new CustomAthenzHeader("X-Company-Token");
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(USER_ROLE)
                                                    .tokenHeader(customHeader)
                                                    .newDecorator())
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/api");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(capturedHeaderName.get()).isEqualToIgnoringCase("X-Company-Token");
            assertThat(capturedHeaderValue.get()).isNotEmpty();
        }
    }

    @Test
    void tokenTypeAccessTokenUsesAuthorizationHeader() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient("foo-service")) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(USER_ROLE)
                                                    .tokenHeader(TokenType.ACCESS_TOKEN)
                                                    .newDecorator())
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/api");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(capturedHeaderName.get()).isEqualToIgnoringCase("Authorization");
            assertThat(capturedHeaderValue.get()).startsWith("Bearer ");
        }
    }

    private static final class CustomAthenzHeader implements AthenzTokenHeader {
        private final String headerName;
        private final AsciiString asciiHeaderName;

        CustomAthenzHeader(String headerName) {
            this.headerName = headerName;
            this.asciiHeaderName = AsciiString.of(headerName);
        }

        @Override
        public String name() {
            return "CUSTOM_" + headerName.toUpperCase().replace('-', '_');
        }

        @Override
        public AsciiString headerName() {
            return asciiHeaderName;
        }

        @Override
        public String authScheme() {
            return null;
        }

        @Override
        public boolean isRoleToken() {
            return false;
        }
    }
}
