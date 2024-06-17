/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionTimeoutException;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.internal.testing.CountDownEmptyEndpointStrategy;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingClientWithEmptyEndpointGroupTest {

    private static final String CUSTOM_HEADER = "custom-header";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/1", (ctx, req) -> HttpResponse.of(200))
              .decorator(LoggingService.builder()
                                       .logWriter(LogWriter.builder()
                                                           .successfulResponseLogLevel(LogLevel.INFO)
                                                           .build())
                                       .newDecorator());
        }
    };

    @Test
    void shouldRetryEvenIfEndpointGroupIsEmpty() {
        final int numAttempts = 3;
        final WebClient client =
                WebClient.builder(SessionProtocol.HTTP, EndpointGroup.of())
                         .decorator(RetryingClient.builder(RetryRule.builder()
                                                                    .onUnprocessed()
                                                                    .thenBackoff(Backoff.withoutDelay()))
                                                  .maxTotalAttempts(numAttempts)
                                                  .newDecorator())
                         .build();

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.get("/").aggregate();
            ctx = ctxCaptor.get();
        }

        // Make sure all attempts have failed with `EmptyEndpointGroupException`.
        final RequestLog log = ctx.log().whenComplete().join();
        assertEmptyEndpointGroupException(log);

        assertThat(log.children()).hasSize(numAttempts);
        for (int i = 0; i < log.children().size(); i++) {
            assertThat(log.children().get(i).partial().currentAttempt()).isEqualTo(i + 1);
        }

        log.children().stream()
           .map(RequestLogAccess::ensureComplete)
           .forEach(RetryingClientWithEmptyEndpointGroupTest::assertEmptyEndpointGroupException);
    }

    @Test
    void testSelectionTimeout() {
        final int maxTotalAttempts = 3;

        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        final WebClient webClient = WebClient
                .builder(SessionProtocol.HTTP, endpointGroup)
                .responseTimeout(Duration.ZERO) // since retry can depend on responseTimeout
                .decorator(RetryingClient.builder(RetryRule.onUnprocessed())
                                         .maxTotalAttempts(maxTotalAttempts)
                                         .newDecorator())
                .decorator(LoggingClient.builder()
                                        .logWriter(LogWriter.builder()
                                                            .successfulResponseLogLevel(LogLevel.INFO)
                                                            .build())
                                        .newDecorator())
                .build();

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> webClient.get("/").aggregate().join())
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCauseInstanceOf(EndpointSelectionTimeoutException.class);

            assertThat(ctxCaptor.size()).isEqualTo(1);
            assertThat(ctxCaptor.get().log().children()).hasSize(maxTotalAttempts);
            ctxCaptor.get().log().children().forEach(log -> {
                final Throwable responseCause = log.whenComplete().join().responseCause();
                assertThat(responseCause)
                        .isInstanceOf(UnprocessedRequestException.class)
                        .hasCauseInstanceOf(EndpointSelectionTimeoutException.class);
            });
        }
    }

    @Test
    void testSelectionTimeoutSucceedsEventually() {
        final int maxTotalAttempts = 10;
        final int selectAttempts = 3;

        final DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup(
                new CountDownEmptyEndpointStrategy(selectAttempts,
                                                   unused -> completedFuture(server.httpEndpoint())));

        final WebClient webClient = WebClient
                .builder(SessionProtocol.HTTP, endpointGroup)
                .contextCustomizer(ctx -> ctx.addAdditionalRequestHeader(CUSTOM_HEADER, "asdf"))
                .responseTimeout(Duration.ZERO) // since retry can depend on responseTimeout
                .decorator(LoggingClient.builder()
                                        .logWriter(LogWriter.builder()
                                                            .requestLogLevel(LogLevel.INFO)
                                                            .build())
                                        .newDecorator())
                .decorator(RetryingClient.builder(RetryRule.onUnprocessed())
                                         .maxTotalAttempts(maxTotalAttempts)
                                         .newDecorator())
                .build();

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response = webClient.get("/1").aggregate().join();
            assertThat(response.status().code()).isEqualTo(200);

            assertThat(ctxCaptor.size()).isEqualTo(1);
            assertThat(ctxCaptor.get().log().children()).hasSize(selectAttempts);

            // ensure that selection timeout occurred (selectAttempts - 1) times
            for (int i = 0; i < selectAttempts - 1; i++) {
                final RequestLogAccess log = ctxCaptor.get().log().children().get(i);
                final Throwable responseCause = log.whenComplete().join().responseCause();
                assertThat(responseCause)
                        .isInstanceOf(UnprocessedRequestException.class)
                        .hasCauseInstanceOf(EndpointSelectionTimeoutException.class);
            }

            // ensure that the last selection succeeded
            final RequestLogAccess log = ctxCaptor.get().log().children().get(selectAttempts - 1);
            assertThat(log.whenComplete().join().responseStatus().code())
                    .isEqualTo(200);

            // context customizer should be run only once
            assertThat(log.whenRequestComplete().join().requestHeaders().getAll(CUSTOM_HEADER)).hasSize(1);
        }
    }

    private static void assertEmptyEndpointGroupException(RequestLog log) {
        assertThat(log.responseCause()).isInstanceOf(UnprocessedRequestException.class)
                                       .hasCauseInstanceOf(EmptyEndpointGroupException.class);
    }
}
