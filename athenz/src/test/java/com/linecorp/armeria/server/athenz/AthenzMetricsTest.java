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
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.List;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
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
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.AccessDeniedException;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@EnabledIfDockerAvailable
class AthenzMetricsTest {

    private static final MeterRegistry serverMeterRegistry = new SimpleMeterRegistry();

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
                                                 .meterIdPrefix(new MeterIdPrefix("athenz.service.test"))
                                                 .build();
            sb.annotatedService(new AthenzAnnotatedService());
            final DependencyInjector di = DependencyInjector.ofSingletons(decoratorFactory)
                                                            .orElse(DependencyInjector.ofReflective());
            sb.dependencyInjector(di, true);
            sb.meterRegistry(serverMeterRegistry);
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
    void testMetrics(String serviceName, TokenType tokenType, boolean shouldSucceed) {

        final Timer serviceAllowed = serverMeterRegistry.get("athenz.service.test.token.authorization")
                                                        .tag("result", "allowed")
                                                        .tag("resource", "secrets")
                                                        .tag("action", "obtain")
                                                        .timer();
        final long numServiceAllowed = serviceAllowed.count();
        final Timer serviceDenied = serverMeterRegistry.get("athenz.service.test.token.authorization")
                                                       .tag("result", "denied")
                                                       .tag("resource", "secrets")
                                                       .tag("action", "obtain")
                                                       .timer();
        final long numServiceDenied = serviceDenied.count();

        final MeterRegistry clientMeterRegistry = new SimpleMeterRegistry();
        try (ZtsBaseClient ztsBaseClient = athenzExtension.newZtsBaseClient(serviceName, cb -> {
            cb.enableMetrics(clientMeterRegistry, MeterIdPrefixFunction.ofDefault("athenz.zts.client.test"));
        })) {
            final BlockingWebClient client =
                    WebClient.builder(server.httpUri())
                             .decorator(AthenzClient.builder(ztsBaseClient)
                                                    .domainName(TEST_DOMAIN_NAME)
                                                    .roleNames(ADMIN_ROLE)
                                                    .tokenType(tokenType)
                                                    .meterIdPrefix(new MeterIdPrefix("athenz.client.test"))
                                                    .newDecorator())
                             .build()
                             .blocking();

            if (shouldSucceed) {
                final AggregatedHttpResponse response = client.get("/secrets");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThatJson(response.contentUtf8()).isEqualTo(ImmutableList.of("Armeria", "Athenz"));
                await().untilAsserted(() -> {
                    final Timer clientSuccess = clientMeterRegistry.get("athenz.client.test.token.fetch")
                                                                   .tag("result", "success")
                                                                   .tag("domain", TEST_DOMAIN_NAME)
                                                                   .tag("roles", ADMIN_ROLE)
                                                                   .tag("type", tokenType.name())
                                                                   .timer();

                    final Timer clientFailure = clientMeterRegistry.get("athenz.client.test.token.fetch")
                                                                   .tag("result", "failure")
                                                                   .tag("domain", TEST_DOMAIN_NAME)
                                                                   .tag("roles", ADMIN_ROLE)
                                                                   .tag("type", tokenType.name())
                                                                   .timer();
                    assertThat(clientSuccess.count()).isEqualTo(1);
                    assertThat(clientFailure.count()).isZero();
                    assertThat(MoreMeters.measureAll(clientMeterRegistry))
                            .anySatisfy((meterId, value) -> {
                                assertThat(meterId).startsWith("athenz.zts.client.test");
                            });
                    assertThat(serviceAllowed.count() - numServiceAllowed).isEqualTo(1);
                    assertThat(serviceDenied.count() - numServiceDenied).isZero();
                });
            } else {
                assertThatThrownBy(() -> client.get("/secrets"))
                        .isInstanceOf(AccessDeniedException.class)
                        .hasMessage("Failed to obtain an Athenz %s token. " +
                                    "(domain: testing, roles: test_role_admin)",
                                    tokenType.isRoleToken() ? "role" : "access");

                await().untilAsserted(() -> {
                    final Timer clientSuccess = clientMeterRegistry.get("athenz.client.test.token.fetch")
                                                                   .tag("result", "success")
                                                                   .tag("domain", TEST_DOMAIN_NAME)
                                                                   .tag("roles", ADMIN_ROLE)
                                                                   .tag("type", tokenType.name())
                                                                   .timer();

                    final Timer clientFailure = clientMeterRegistry.get("athenz.client.test.token.fetch")
                                                                   .tag("result", "failure")
                                                                   .tag("domain", TEST_DOMAIN_NAME)
                                                                   .tag("roles", ADMIN_ROLE)
                                                                   .tag("type", tokenType.name())
                                                                   .timer();
                    assertThat(clientSuccess.count()).isZero();
                    assertThat(clientFailure.count()).isEqualTo(1);
                    assertThat(MoreMeters.measureAll(clientMeterRegistry))
                            .anySatisfy((meterId, value) -> {
                                assertThat(meterId).startsWith("athenz.zts.client.test");
                            });
                    assertThat(serviceAllowed.count() - numServiceAllowed).isZero();
                    // The access was denied at the client side, so the server's denied count should not
                    // increase.
                    assertThat(serviceDenied.count() - numServiceDenied).isZero();
                });
            }
        }
    }

    @Test
    void shouldRejectTokenAtServerSide() {
        final Timer serviceAllowed = serverMeterRegistry.get("athenz.service.test.token.authorization")
                                                        .tag("result", "allowed")
                                                        .tag("resource", "secrets")
                                                        .tag("action", "obtain")
                                                        .timer();
        final long numServiceAllowed = serviceAllowed.count();
        final Timer serviceDenied = serverMeterRegistry.get("athenz.service.test.token.authorization")
                                                       .tag("result", "denied")
                                                       .tag("resource", "secrets")
                                                       .tag("action", "obtain")
                                                       .timer();
        final long numServiceDenied = serviceDenied.count();
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse response = client.prepare()
                                                      .get("/secrets")
                                                      .header(HttpHeaderNames.AUTHORIZATION, "invalid-token")
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        await().untilAsserted(() -> {
            assertThat(serviceAllowed.count() - numServiceAllowed).isZero();
            assertThat(serviceDenied.count() - numServiceDenied).isEqualTo(1);
        });
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
