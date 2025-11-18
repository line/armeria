/*
 * Copyright 2025 LY Corporation
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

import static com.linecorp.armeria.server.athenz.AthenzDocker.ADMIN_ROLE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.FOO_SERVICE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.USER_ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.AccessDeniedException;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@EnabledIfDockerAvailable
class AthenzIntegrationTest {

    @Order(1)
    @RegisterExtension
    static final AthenzExtension athenzExtension = new AthenzExtension();

    @Order(2)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // test-service acts as the provider application.
            final ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE);
            sb.serverListener(ServerListener.builder()
                                            .whenStopped(s -> ztsBaseClient.close())
                                            .build());
            sb.service("/admin", (ctx, req) -> {
                String authorization = req.headers().get(HttpHeaderNames.AUTHORIZATION, "");
                if (!authorization.isEmpty()) {
                    return HttpResponse.of("Authorization " + authorization);
                }
                authorization = req.headers().get(HttpHeaderNames.YAHOO_ROLE_AUTH, "");
                if (!authorization.isEmpty()) {
                    return HttpResponse.of("YahooRoleAuth " + authorization);
                }
                authorization = req.headers().get(HttpHeaderNames.ATHENZ_ROLE_AUTH, "");
                if (!authorization.isEmpty()) {
                    return HttpResponse.of("AthenzRoleAuth " + authorization);
                }
                // Should not reach here.
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
            });

            sb.decorator("/admin", AthenzService.builder(ztsBaseClient)
                                                .action("obtain")
                                                .resource("secrets")
                                                .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                .newDecorator());

            sb.service("/users", (ctx, req) -> {
                String authorization = req.headers().get(HttpHeaderNames.AUTHORIZATION, "");
                if (!authorization.isEmpty()) {
                    return HttpResponse.of("Authorization " + authorization);
                }
                authorization = req.headers().get(HttpHeaderNames.YAHOO_ROLE_AUTH, "");
                if (!authorization.isEmpty()) {
                    return HttpResponse.of("YahooRoleAuth " + authorization);
                }
                authorization = req.headers().get(HttpHeaderNames.ATHENZ_ROLE_AUTH, "");
                if (!authorization.isEmpty()) {
                    return HttpResponse.of("AthenzRoleAuth " + authorization);
                }
                // Should not reach here.
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
            });
            sb.decorator("/users", AthenzService.builder(ztsBaseClient)
                                                .action("obtain")
                                                .resource("files")
                                                .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                .newDecorator());
        }
    };

    @EnumSource(TokenType.class)
    @ParameterizedTest
    void shouldObtainAdminRole(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .responseTimeoutMillis(0)
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/admin");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            switch (tokenType) {
                case YAHOO_ROLE_TOKEN:
                    assertThat(response.contentUtf8()).startsWith("YahooRoleAuth ");
                    break;
                case ATHENZ_ROLE_TOKEN:
                    assertThat(response.contentUtf8()).startsWith("AthenzRoleAuth ");
                    break;
                case ACCESS_TOKEN:
                    assertThat(response.contentUtf8()).startsWith("Authorization ");
                    break;
            }
        }
    }

    @EnumSource(TokenType.class)
    @ParameterizedTest
    void shouldNotObtainAdminRole(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(FOO_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .build()
                             .blocking();
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                assertThatThrownBy(() -> {
                    client.get("/admin");
                }).isInstanceOf(AccessDeniedException.class)
                  .hasMessage("Failed to obtain an Athenz " +
                              (tokenType.isRoleToken() ? "role" : "access") +
                              " token. (domain: testing, roles: test_role_admin)");
                final ClientRequestContext ctx = captor.get();
                // Make sure the RequestLog is completed when the request was rejected by the decorator.
                ctx.log().whenComplete().join();
            }
        }
    }

    @EnumSource(TokenType.class)
    @ParameterizedTest
    void shouldRejectImproperRole(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(FOO_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  USER_ROLE, tokenType))
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/admin");
            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
