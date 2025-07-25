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

import static com.linecorp.armeria.server.athenz.AthenzExtension.ADMIN_ROLE;
import static com.linecorp.armeria.server.athenz.AthenzExtension.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzExtension.TEST_SERVICE;
import static com.linecorp.armeria.server.athenz.AthenzExtension.USER_ROLE;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.AccessDeniedException;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@EnabledIfDockerAvailable
class AthenzAnnotatedServiceTest {

    @Order(1)
    @RegisterExtension
    static final AthenzExtension athenzExtension = new AthenzExtension();

    @Order(2)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ZtsBaseClient baseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE);
            final AthenzServiceDecoratorFactory decoratorFactory =
                    AthenzServiceDecoratorFactory.builder(baseClient)
                                                 .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                                                 .build();
            sb.annotatedService(new AthenzAnnotatedService());
            final DependencyInjector di = DependencyInjector.ofSingletons(decoratorFactory)
                                                            .orElse(DependencyInjector.ofReflective());
            sb.dependencyInjector(di, true);
            sb.serverListener(ServerListener.builder()
                                            .whenStopped(server -> baseClient.close())
                                            .build());
        }
    };

    @CsvSource({
            "foo-service, ROLE_TOKEN",
            "foo-service, ACCESS_TOKEN",
            "test-service, ROLE_TOKEN",
            "test-service, ACCESS_TOKEN"
    })
    @ParameterizedTest
    void testUserRole(String serviceName, TokenType tokenType) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(serviceName)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  USER_ROLE, tokenType))
                             .build()
                             .blocking();

            final AggregatedHttpResponse response = client.get("/files");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThatJson(response.contentUtf8()).isEqualTo(ImmutableList.of("foo.txt", "bar.txt"));
        }
    }

    @CsvSource({
            "foo-service, ROLE_TOKEN, false",
            "foo-service, ACCESS_TOKEN, false",
            "test-service, ROLE_TOKEN, true",
            "test-service, ACCESS_TOKEN, true"
    })
    @ParameterizedTest
    void testAdminRole(String serviceName, TokenType tokenType, boolean shouldSucceed) {
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(serviceName)) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.newDecorator(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                  ADMIN_ROLE, tokenType))
                             .build()
                             .blocking();

            if (shouldSucceed) {
                final AggregatedHttpResponse response = client.get("/secrets");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThatJson(response.contentUtf8()).isEqualTo(ImmutableList.of("Armeria", "Athenz"));
            } else {
                assertThatThrownBy(() -> client.get("/secrets"))
                        .isInstanceOf(AccessDeniedException.class)
                        .hasMessage("Failed to obtain an Athenz %s token. " +
                                    "(domain: testing, roles: test_role_admin)",
                                    tokenType == TokenType.ROLE_TOKEN ? "role" : "access");
            }
        }
    }

    private static final class AthenzAnnotatedService {

        @RequiresAthenzRole(action = "obtain", resource = "secrets")
        @Get("/secrets")
        @ProducesJson
        public List<String> getSecrets() {
            return ImmutableList.of("Armeria", "Athenz");
        }

        @RequiresAthenzRole(action = "obtain", resource = "files")
        @Get("/files")
        @ProducesJson
        public List<String> getFiles() {
            return ImmutableList.of("foo.txt", "bar.txt");
        }
    }
}
