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
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.USER_ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.athenz.resource.AthenzResourceProvider;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@EnabledIfDockerAvailable
class AthenzResourceProviderIntegrationTest {

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

            // PathAthenzResourceProvider test endpoint
            sb.service("/admin/path/test", (ctx, req) -> HttpResponse.of("Path resource accessed"));
            sb.decorator("/admin/path/test", AthenzService.builder(ztsBaseClient)
                                                          .action("obtain")
                                                          .resourceProvider(AthenzResourceProvider.ofPath())
                                                          .policyConfig(
                                                                  new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                          .newDecorator());

            // HeaderAthenzResourceProvider test endpoint
            sb.service("/admin/header/test", (ctx, req) -> HttpResponse.of("Header resource accessed"));
            sb.decorator("/admin/header/test", AthenzService.builder(ztsBaseClient)
                                                            .action("obtain")
                                                            .resourceProvider(AthenzResourceProvider.ofHeader(
                                                                    "X-Athenz-Resource"))
                                                            .policyConfig(
                                                                    new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                            .newDecorator());

            // JsonBodyFieldAthenzResourceProvider test endpoint
            sb.service("/admin/json/test", (ctx, req) -> HttpResponse.of("JSON resource accessed"));
            sb.decorator("/admin/json/test", AthenzService.builder(ztsBaseClient)
                                                          .action("obtain")
                                                          .resourceProvider(AthenzResourceProvider.ofJsonField(
                                                                  "/resource"))
                                                          .policyConfig(
                                                                  new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                          .newDecorator());

            // Exception test endpoint
            sb.service("/admin/exception/test", (ctx, req) -> HttpResponse.of("Should not reach here"));
            sb.decorator("/admin/exception/test", AthenzService.builder(ztsBaseClient)
                                                               .action("obtain")
                                                               .resourceProvider((ctx, request) -> {
                                                                   throw new IllegalArgumentException(
                                                                           "Simulated resource provider " +
                                                                           "exception");
                                                               }).policyConfig(
                            new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                               .newDecorator());

            // Empty resource test endpoint
            sb.service("/admin/null/test", (ctx, req) -> HttpResponse.of("Should not reach here"));
            sb.decorator("/admin/null/test", AthenzService.builder(ztsBaseClient)
                                                          .action("obtain")
                                                          .resourceProvider(
                                                                  (ctx, request) ->
                                                                          UnmodifiableFuture.completedFuture(
                                                                                  null))
                                                          .policyConfig(
                                                                  new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                          .newDecorator());

            // Empty resource test endpoint
            sb.service("/admin/empty/test", (ctx, req) -> HttpResponse.of("Should not reach here"));
            sb.decorator("/admin/empty/test", AthenzService.builder(ztsBaseClient)
                                                           .action("obtain")
                                                           .resourceProvider(
                                                                   (ctx, request) ->
                                                                           UnmodifiableFuture.completedFuture(
                                                                                   ""))
                                                           .policyConfig(
                                                                   new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                           .newDecorator());
        }
    };

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void shouldAllowAccessWithPathResource(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .responseTimeoutMillis(0)
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/admin/path/test");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Path resource accessed");
        }
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void shouldDenyAccessWhenRoleIsUser(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  USER_ROLE, tokenType))
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/admin/path/test");
            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void shouldAllowAccessWithHeaderResource(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .responseTimeoutMillis(0)
                             .build()
                             .blocking();

            final AggregatedHttpResponse response =
                    client.execute(RequestHeaders.builder()
                                                 .method(HttpMethod.GET)
                                                 .path("/admin/header/test")
                                                 .add("X-Athenz-Resource", "header-resource")
                                                 .build());
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("Header resource accessed");
        }
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void shouldDenyAccessWithInvalidHeaderResource(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .build()
                             .blocking();

            final AggregatedHttpResponse response =
                    client.execute(RequestHeaders.builder()
                                                 .method(HttpMethod.GET)
                                                 .path("/admin/header/test")
                                                 .add("X-Athenz-Resource", "invalid-header-resource")
                                                 .build());
            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void shouldAllowAccessWithJsonBodyResource(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .build()
                             .blocking();

            final String jsonBody = "{\"resource\":\"body-resource\",\"data\":\"test\"}";
            final AggregatedHttpResponse response =
                    client.execute(RequestHeaders.builder()
                                                 .method(HttpMethod.POST)
                                                 .path("/admin/json/test")
                                                 .contentType(MediaType.JSON)
                                                 .build(),
                                   HttpData.ofUtf8(jsonBody));
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("JSON resource accessed");
        }
    }

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void shouldDenyAccessWithInvalidJsonBodyResource(TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .build()
                             .blocking();

            final String jsonBody = "{\"resource\":\"invalid-body-resource\",\"data\":\"test\"}";
            final AggregatedHttpResponse response =
                    client.execute(RequestHeaders.builder()
                                                 .method(HttpMethod.POST)
                                                 .path("/admin/json/test")
                                                 .contentType(MediaType.JSON)
                                                 .build(),
                                   HttpData.ofUtf8(jsonBody));
            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void shouldHandleResourceProviderException() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME, ADMIN_ROLE,
                                                                  TokenType.ACCESS_TOKEN))
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/admin/exception/test");
            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void shouldHandleNullStringResource() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME, ADMIN_ROLE,
                                                                  TokenType.ACCESS_TOKEN))
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/admin/null/test");
            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void shouldHandleEmptyStringResource() {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME, ADMIN_ROLE,
                                                                  TokenType.ACCESS_TOKEN))
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/admin/empty/test");
            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
