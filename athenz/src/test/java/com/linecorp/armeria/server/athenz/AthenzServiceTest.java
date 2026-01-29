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

package com.linecorp.armeria.server.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.USER_ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AsciiString;

@EnabledIfDockerAvailable
class AthenzServiceTest {

    @Order(1)
    @RegisterExtension
    static final AthenzExtension athenzExtension = new AthenzExtension();

    @Order(2)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE);
            sb.serverListener(ServerListener.builder()
                                            .whenStopped(s -> ztsBaseClient.close())
                                            .build());

            // Test service with multiple custom headers
            sb.service("/api/custom", (ctx, req) -> HttpResponse.of("OK"));
            sb.decorator("/api/custom", AthenzService.builder(ztsBaseClient)
                                                     .action("read")
                                                     .resource("data")
                                                     .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                     .tokenHeader(new CustomHeader("X-Company-Token"))
                                                     .tokenHeader(new CustomHeader("X-Alt-Token"))
                                                     .tokenHeader(TokenType.ACCESS_TOKEN)
                                                     .newDecorator());

            // Test service with header varargs
            sb.service("/api/varargs", (ctx, req) -> HttpResponse.of("OK"));
            sb.decorator("/api/varargs", AthenzService.builder(ztsBaseClient)
                                                      .action("read")
                                                      .resource("data")
                                                      .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                      .tokenHeader(new CustomHeader("X-Token-1"),
                                                                   new CustomHeader("X-Token-2"),
                                                                   TokenType.ATHENZ_ROLE_TOKEN)
                                                      .newDecorator());

            // Test service with header Iterable
            sb.service("/api/iterable", (ctx, req) -> HttpResponse.of("OK"));
            sb.decorator("/api/iterable", AthenzService.builder(ztsBaseClient)
                                                       .action("read")
                                                       .resource("data")
                                                       .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                       .tokenHeader(Arrays.asList(
                                                               new CustomHeader("X-List-Token-1"),
                                                               new CustomHeader("X-List-Token-2"),
                                                               TokenType.YAHOO_ROLE_TOKEN))
                                                       .newDecorator());
        }
    };

    @Test
    void customHeaderIsAcceptedByServer() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient("foo-service")) {
            final CustomHeader customHeader = new CustomHeader("X-Company-Token");
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(USER_ROLE)
                                                    .tokenHeader(customHeader)
                                                    .newDecorator())
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/api/custom");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }

    @Test
    void multipleHeadersAreCheckedInOrder() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient("foo-service")) {
            // Send token via second header (X-Alt-Token)
            final CustomHeader altHeader = new CustomHeader("X-Alt-Token");
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(USER_ROLE)
                                                    .tokenHeader(altHeader)
                                                    .newDecorator())
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/api/custom");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }

    @Test
    void accessTokenHeaderIsAcceptedInMultiHeaderConfig() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient("foo-service")) {
            // Send token via Authorization header (ACCESS_TOKEN)
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(USER_ROLE)
                                                    .tokenHeader(TokenType.ACCESS_TOKEN)
                                                    .newDecorator())
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/api/custom");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }

    @Test
    void varargsHeadersAreAccepted() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient("foo-service")) {
            final CustomHeader header1 = new CustomHeader("X-Token-1");
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(USER_ROLE)
                                                    .tokenHeader(header1)
                                                    .newDecorator())
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/api/varargs");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }

    @Test
    void iterableHeadersAreAccepted() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient("foo-service")) {
            final CustomHeader header2 = new CustomHeader("X-List-Token-2");
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(USER_ROLE)
                                                    .tokenHeader(header2)
                                                    .newDecorator())
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/api/iterable");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }

    @Test
    void unauthorizedWhenNoValidHeaderPresent() {
        final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                   .build()
                                                   .blocking();

        final AggregatedHttpResponse response = client.get("/api/custom");
        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static final class CustomHeader implements AthenzTokenHeader {
        private final String headerName;
        private final AsciiString asciiHeaderName;

        CustomHeader(String headerName) {
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
            return true;
        }
    }
}
