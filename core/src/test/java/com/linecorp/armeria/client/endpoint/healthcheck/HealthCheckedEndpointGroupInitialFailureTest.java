/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HealthCheckedEndpointGroupInitialFailureTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.virtualHost("health.foo.com")
              .service("/health", (ctx, req) -> {
                  return HttpResponse.of(HttpStatus.OK);
              });
            sb.virtualHost("health.bar.com")
              .service("/health", (ctx, req) -> {
                  return HttpResponse.of(HttpStatus.OK);
              });

            sb.virtualHost("slow.foo.com")
              .service("/health", (ctx, req) -> {
                  return HttpResponse.streaming();
              });
            sb.virtualHost("slow.bar.com")
              .service("/health", (ctx, req) -> {
                  return HttpResponse.streaming();
              });

            sb.virtualHost("500.foo.com")
              .service("/health", (ctx, req) -> {
                  return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
              });
            sb.virtualHost("500.bar.com")
              .service("/health", (ctx, req) -> {
                  return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
              });
            // Make clients trigger timeouts.
            sb.requestTimeoutMillis(0);
        }
    };

    static ClientFactory clientFactory;

    @BeforeAll
    static void beforeAll() {
        clientFactory = ClientFactory.builder()
                                     .addressResolverGroupFactory(
                                             unused -> MockAddressResolverGroup.localhost())
                                     .build();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    @Test
    void allHealthy() {
        final EndpointGroup delegate =
                EndpointGroup.of(Endpoint.of("health.foo.com"), Endpoint.of("health.bar.com"));
        try (HealthCheckedEndpointGroup endpointGroup = newHealthCheckedEndpointGroup(delegate)) {
            assertThat(endpointGroup.whenReady().join()).hasSize(2);
            assertThat(endpointGroup.endpointPool().contextGroupChain().size()).isOne();
            assertThat(endpointGroup.endpointPool().contextGroupChain().poll().whenInitialized()).isCompleted();
        }
    }

    @CsvSource({"slow.bar.com", "500.bar.com"})
    @ParameterizedTest
    void oneHealthy(String unhealthyHost) {
        final Endpoint foo = Endpoint.of("health.foo.com");
        final EndpointGroup delegate =
                EndpointGroup.of(foo, Endpoint.of(unhealthyHost));
        try (HealthCheckedEndpointGroup endpointGroup = newHealthCheckedEndpointGroup(delegate)) {
            assertThat(endpointGroup.whenReady().join()).containsExactly(foo);
            assertThat(endpointGroup.endpointPool().contextGroupChain().size()).isOne();
            // Since there is one healthy endpoint, `whenInitialized()` should not complete with an error.
            assertThat(endpointGroup.endpointPool().contextGroupChain()
                                    .poll().whenInitialized()).isCompleted();
        }
    }

    @Test
    void allUnhealthy_responseTimeout() {
        final EndpointGroup delegate =
                EndpointGroup.of(Endpoint.of("slow.foo.com"), Endpoint.of("slow.bar.com"));
        try (HealthCheckedEndpointGroup endpointGroup = newHealthCheckedEndpointGroup(delegate)) {
            assertThat(endpointGroup.whenReady().join()).isEmpty();
            assertThat(endpointGroup.endpointPool().contextGroupChain().size()).isOne();
            final Throwable cause =
                    catchThrowable(() -> endpointGroup.endpointPool().contextGroupChain().poll()
                                                      .whenInitialized().join());
            assertThat(cause)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
            final Throwable[] suppressed = cause.getSuppressed();
            assertThat(suppressed).hasSize(1);
            assertThat(suppressed[0]).isInstanceOf(ResponseTimeoutException.class);
        }
    }

    @Test
    void allUnhealthy_errorResponse() {
        final EndpointGroup delegate =
                EndpointGroup.of(Endpoint.of("500.foo.com"), Endpoint.of("500.bar.com"));
        try (HealthCheckedEndpointGroup endpointGroup = newHealthCheckedEndpointGroup(delegate)) {
            assertThat(endpointGroup.whenReady().join()).isEmpty();
            assertThat(endpointGroup.endpointPool().contextGroupChain().size()).isOne();
            final Throwable cause =
                    catchThrowable(() -> endpointGroup.endpointPool().contextGroupChain()
                                                      .poll().whenInitialized().join());
            assertThat(cause)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("Received an unhealthy check response.");
            final Throwable[] suppressed = cause.getSuppressed();
            assertThat(suppressed).hasSize(1);
            assertThat(suppressed[0]).isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("Received an unhealthy check response.");
        }
    }

    @Test
    void allUnhealthy_mixed() {
        final EndpointGroup delegate =
                EndpointGroup.of(Endpoint.of("slow.foo.com"), Endpoint.of("500.bar.com"));
        try (HealthCheckedEndpointGroup endpointGroup = newHealthCheckedEndpointGroup(delegate)) {
            assertThat(endpointGroup.whenReady().join()).isEmpty();
            assertThat(endpointGroup.endpointPool().contextGroupChain().size()).isOne();
            final Throwable cause =
                    catchThrowable(() -> endpointGroup.endpointPool().contextGroupChain()
                                                      .poll().whenInitialized().join());
            assertThat(cause)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
            final Throwable[] suppressed = cause.getSuppressed();
            assertThat(suppressed).hasSize(1);
            assertThat(suppressed[0]).isInstanceOf(InvalidResponseException.class)
                    .hasMessageContaining("Received an unhealthy check response.");
        }
    }

    @Test
    void timeoutExceptionContainsEndpointGroupInfo() throws Exception {
        final EndpointGroup delegate =
                EndpointGroup.of(Endpoint.of("slow.foo.com"), Endpoint.of("slow.bar.com"));
        try (HealthCheckedEndpointGroup endpointGroup = newHealthCheckedEndpointGroup(delegate)) {
            assertThatThrownBy(() -> endpointGroup.whenReady().get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class)
                    .hasMessageContaining("endpoint group: HealthCheckedEndpointGroup");
        }
    }

    private static HealthCheckedEndpointGroup newHealthCheckedEndpointGroup(EndpointGroup delegate) {
        return HealthCheckedEndpointGroup.builder(delegate, "/health")
                                         .port(server.httpPort())
                                         .clientFactory(clientFactory)
                                         .withClientOptions(builder -> builder.responseTimeoutMillis(3000))
                                         .build();
    }
}
