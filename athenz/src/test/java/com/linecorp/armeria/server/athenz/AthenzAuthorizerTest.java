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

// ...existing code...
package com.linecorp.armeria.server.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.ADMIN_ROLE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@EnabledIfDockerAvailable
class AthenzAuthorizerTest {

    private static String extractToken(RequestHeaders headers) {
        for (TokenType tokenType : TokenType.values()) {
            final String token = headers.get(tokenType.headerName(), "");
            if (!token.isEmpty()) {
                return token;
            }
        }
        return null;
    }

    @Order(1)
    @RegisterExtension
    static final AthenzExtension athenzExtension = new AthenzExtension();

    @Order(2)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ZtsBaseClient baseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE);
            final AthenzAuthorizer authorizer =
                    AthenzAuthorizer.builder(baseClient)
                                    .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                    .build();

            sb.service("/secrets", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future =
                        CompletableFuture.supplyAsync(() -> {
                            final RequestHeaders headers = req.headers();
                            final String token = extractToken(headers);
                            final AccessCheckStatus status =
                                    authorizer.authorize(token, "secrets", "obtain");
                            if (status == AccessCheckStatus.ALLOW) {
                                return HttpResponse.of("Authorized");
                            }
                            return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                        }, ctx.blockingTaskExecutor());
                return HttpResponse.of(future);
            });

            sb.service("/secrets-async", (ctx, req) -> {
                final RequestHeaders headers = req.headers();
                final String token = extractToken(headers);
                final CompletableFuture<AccessCheckStatus> statusFuture =
                        authorizer.authorizeAsync(token, "secrets", "obtain");
                final CompletableFuture<HttpResponse> respFuture =
                        statusFuture.thenApply(status -> {
                            if (status == AccessCheckStatus.ALLOW) {
                                return HttpResponse.of("Authorized");
                            }
                            return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                        });
                return HttpResponse.of(respFuture);
            });

            sb.serverListener(ServerListener.builder()
                                            .whenStopped(server -> baseClient.close())
                                            .build());
        }
    };

    @CsvSource({
            "foo-service, YAHOO_ROLE_TOKEN, false",
            "foo-service, ATHENZ_ROLE_TOKEN, false",
            "foo-service, ACCESS_TOKEN, false",
            "test-service, YAHOO_ROLE_TOKEN, true",
            "test-service, ATHENZ_ROLE_TOKEN, true",
            "test-service, ACCESS_TOKEN, true"
    })
    @ParameterizedTest
    void testAdminRole(String serviceName, TokenType tokenType, boolean shouldSucceed) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(serviceName)) {
            if (shouldSucceed) {
                final BlockingWebClient client =
                        WebClient.builder(server.httpUri())
                                 .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                      ADMIN_ROLE, tokenType))
                                 .build()
                                 .blocking();
                final AggregatedHttpResponse response = client.get("/secrets");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);

                // Also test async path
                final AggregatedHttpResponse asyncResponse = client.get("/secrets-async");
                assertThat(asyncResponse.status()).isEqualTo(HttpStatus.OK);
            } else {
                assertThat(server.blockingWebClient()
                                 .prepare()
                                 .get("/secrets")
                                 .header(tokenType.headerName(), tokenType.authScheme() + " invalid")
                                 .execute().status())
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }
    }
}

