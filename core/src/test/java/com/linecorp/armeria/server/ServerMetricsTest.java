/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServerMetricsTest {

    @RegisterExtension
    private static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(0)
              .requestAutoAbortDelayMillis(0)
              .service("/ok/http", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      final ServerMetrics serverMetrics = server.server().config().serverMetrics();
                      assertThat(serverMetrics.pendingRequests()).isZero();
                      if (ctx.sessionProtocol().isMultiplex()) {
                          assertThat(serverMetrics.activeHttp2Requests()).isOne();
                      } else {
                          assertThat(serverMetrics.activeHttp1Requests()).isOne();
                      }
                      assertThat(serverMetrics.activeRequests()).isOne();
                      return HttpResponse.of("Hello, world!");
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              })
              .service("/server-error/http1", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      final ServerMetrics serverMetrics = server.server().config().serverMetrics();
                      assertThat(serverMetrics.pendingRequests()).isZero();
                      assertThat(serverMetrics.activeHttp1Requests()).isOne();
                      assertThat(serverMetrics.activeRequests()).isOne();
                      throw new IllegalArgumentException("Oops!");
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              }).service("/server-error/http2", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      final ServerMetrics serverMetrics = server.server().config().serverMetrics();
                      assertThat(serverMetrics.pendingRequests()).isZero();
                      assertThat(serverMetrics.activeHttp2Requests()).isOne();
                      assertThat(serverMetrics.activeRequests()).isOne();
                      throw new IllegalArgumentException("Oops!");
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              }).service("/request-timeout/http", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      final ServerMetrics serverMetrics = server.server().config().serverMetrics();
                      assertThat(serverMetrics.pendingRequests()).isZero();
                      if (ctx.sessionProtocol().isMultiplex()) {
                          assertThat(serverMetrics.activeHttp2Requests()).isOne();
                      } else {
                          assertThat(serverMetrics.activeHttp1Requests()).isOne();
                      }
                      assertThat(serverMetrics.activeRequests()).isOne();
                      ctx.timeoutNow();
                      return HttpResponse.delayed(HttpResponse.of(200), Duration.ofSeconds(1));
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              });
        }
    };

    @Test
    void pendingRequests() {
        final ServerMetrics serverMetrics = new ServerMetrics();

        serverMetrics.increasePendingHttp1Requests();
        assertThat(serverMetrics.pendingRequests()).isEqualTo(1);

        serverMetrics.increasePendingHttp2Requests();
        assertThat(serverMetrics.pendingRequests()).isEqualTo(2);

        serverMetrics.decreasePendingHttp1Requests();
        assertThat(serverMetrics.pendingRequests()).isEqualTo(1);

        serverMetrics.decreasePendingHttp2Requests();
        assertThat(serverMetrics.pendingRequests()).isZero();
    }

    @Test
    void activeRequests() {
        final ServerMetrics serverMetrics = new ServerMetrics();

        serverMetrics.increaseActiveHttp1Requests();
        assertThat(serverMetrics.activeRequests()).isEqualTo(1);

        serverMetrics.increaseActiveHttp1WebSocketRequests();
        assertThat(serverMetrics.activeRequests()).isEqualTo(2);

        serverMetrics.increaseActiveHttp2Requests();
        assertThat(serverMetrics.activeRequests()).isEqualTo(3);

        serverMetrics.decreaseActiveHttp1WebSocketRequests();
        assertThat(serverMetrics.activeRequests()).isEqualTo(2);

        serverMetrics.decreaseActiveHttp1Requests();
        assertThat(serverMetrics.activeRequests()).isEqualTo(1);

        serverMetrics.decreaseActiveHttp2Requests();
        assertThat(serverMetrics.activeRequests()).isZero();
    }

    @CsvSource({ "H1C, 1, 0", "H2C, 0, 1" })
    @ParameterizedTest
    void checkWhenOk(SessionProtocol sessionProtocol, long expectedPendingHttp1Request,
                     long expectedPendingHttp2Request) throws InterruptedException {
        // maxConnectionAgeMillis() method is for testing whether activeConnections is decreased.
        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .maxConnectionAgeMillis(1000)
                                                        .build()) {
            final WebClient webClient = WebClient.builder(server.uri(sessionProtocol))
                                                 .factory(clientFactory)
                                                 .build();

            final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, "/ok/http");
            final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request)
                                                                                .aggregate();

            final ServerMetrics serverMetrics = server.server()
                                                      .config()
                                                      .serverMetrics();
            await().until(() -> serverMetrics.pendingRequests() == 1);
            assertThat(serverMetrics.pendingHttp1Requests()).isEqualTo(expectedPendingHttp1Request);
            assertThat(serverMetrics.pendingHttp2Requests()).isEqualTo(expectedPendingHttp2Request);
            assertThat(serverMetrics.activeConnections()).isOne();
            request.close();

            final AggregatedHttpResponse result = response.join();

            assertThat(result.status()).isSameAs(HttpStatus.OK);
            assertThat(serverMetrics.pendingRequests()).isZero();
            assertThat(serverMetrics.activeRequests()).isZero();
            await().until(() -> serverMetrics.activeConnections() == 0);
        }
    }

    @CsvSource({ "H1C, /server-error/http1, 1, 0", "H2C, /server-error/http2, 0, 1" })
    @ParameterizedTest
    void checkWhenServerError(SessionProtocol sessionProtocol, String path, long expectedPendingHttp1Request,
                              long expectedPendingHttp2Request) throws InterruptedException {
        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .maxConnectionAgeMillis(1000)
                                                        .build()) {
            final WebClient webClient = WebClient.builder(server.uri(sessionProtocol))
                                                 .factory(clientFactory)
                                                 .build();

            final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, path);
            final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request)
                                                                                .aggregate();

            final ServerMetrics serverMetrics = server.server()
                                                      .config()
                                                      .serverMetrics();
            await().until(() -> serverMetrics.pendingRequests() == 1);
            assertThat(serverMetrics.pendingHttp1Requests()).isEqualTo(expectedPendingHttp1Request);
            assertThat(serverMetrics.pendingHttp2Requests()).isEqualTo(expectedPendingHttp2Request);
            assertThat(serverMetrics.activeConnections()).isOne();
            request.close();

            final AggregatedHttpResponse result = response.join();

            assertThat(result.status()).isSameAs(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(serverMetrics.pendingRequests()).isZero();
            assertThat(serverMetrics.activeRequests()).isZero();
            await().until(() -> serverMetrics.activeConnections() == 0);
        }
    }

    @CsvSource({ "H1C, 1, 0", "H2C, 0, 1" })
    @ParameterizedTest
    void checkWhenRequestTimeout(SessionProtocol sessionProtocol, long expectedPendingHttp1Request,
                                 long expectedPendingHttp2Request) throws InterruptedException {
        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .maxConnectionAgeMillis(1000)
                                                        .build()) {
            final WebClient webClient = WebClient.builder(server.uri(sessionProtocol))
                                                 .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(0L))
                                                 .factory(clientFactory)
                                                 .build();

            final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, "/request-timeout/http");
            final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request)
                                                                                .aggregate();

            final ServerMetrics serverMetrics = server.server()
                                                      .config()
                                                      .serverMetrics();
            await().until(() -> serverMetrics.pendingRequests() == 1);
            assertThat(serverMetrics.pendingHttp1Requests()).isEqualTo(expectedPendingHttp1Request);
            assertThat(serverMetrics.pendingHttp2Requests()).isEqualTo(expectedPendingHttp2Request);
            assertThat(serverMetrics.activeConnections()).isOne();
            request.close();

            final AggregatedHttpResponse result = response.join();

            assertThat(result.status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(serverMetrics.pendingRequests()).isZero();
            assertThat(serverMetrics.activeRequests()).isZero();
            await().until(() -> serverMetrics.activeConnections() == 0);
        }
    }
}
