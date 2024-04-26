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
              .service("/ok", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      ServerMetrics serverMetrics = server.server().config().serverMetrics();
                      assertThat(serverMetrics.pendingRequests()).isZero();
                      assertThat(serverMetrics.activeRequests()).isOne();
                      return HttpResponse.of("Hello, world!");
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              }).service("/server-error", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      ServerMetrics serverMetrics = server.server().config().serverMetrics();
                      assertThat(serverMetrics.pendingRequests()).isZero();
                      assertThat(serverMetrics.activeRequests()).isOne();
                      throw new IllegalArgumentException("Oops!");
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.UNARY;
                  }
              }).service("/request-timeout", new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      ServerMetrics serverMetrics = server.server().config().serverMetrics();
                      assertThat(serverMetrics.pendingRequests()).isZero();
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

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void checkWhenOk(SessionProtocol sessionProtocol) throws InterruptedException {
        // maxConnectionAgeMillis() method is for testing whether activeConnections is decreased.
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .maxConnectionAgeMillis(1000)
                                                         .build();
        final WebClient webClient = WebClient.builder(server.uri(sessionProtocol))
                                             .factory(clientFactory)
                                             .build();

        final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, "/ok");
        final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request)
                                                                            .aggregate();

        final ServerMetrics serverMetrics = server.server()
                                                  .config()
                                                  .serverMetrics();
        await().until(() -> serverMetrics.pendingRequests() == 1);
        assertThat(serverMetrics.activeConnections()).isOne();
        request.close();

        final AggregatedHttpResponse result = response.join();

        assertThat(result.status()).isSameAs(HttpStatus.OK);
        assertThat(serverMetrics.pendingRequests()).isZero();
        assertThat(serverMetrics.activeRequests()).isZero();
        await().until(() -> serverMetrics.activeConnections() == 0);

        clientFactory.close();
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void checkWhenServerError(SessionProtocol sessionProtocol) throws InterruptedException {
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .maxConnectionAgeMillis(1000)
                                                         .build();
        final WebClient webClient = WebClient.builder(server.uri(sessionProtocol))
                                             .factory(clientFactory)
                                             .build();

        final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, "/server-error");
        final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request)
                                                                            .aggregate();

        final ServerMetrics serverMetrics = server.server()
                                                  .config()
                                                  .serverMetrics();
        await().until(() -> serverMetrics.pendingRequests() == 1);
        assertThat(serverMetrics.activeConnections()).isOne();
        request.close();

        final AggregatedHttpResponse result = response.join();

        assertThat(result.status()).isSameAs(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(serverMetrics.pendingRequests()).isZero();
        assertThat(serverMetrics.activeRequests()).isZero();
        await().until(() -> serverMetrics.activeConnections() == 0);

        clientFactory.close();
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void checkWhenRequestTimeout(SessionProtocol sessionProtocol) throws InterruptedException {
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .maxConnectionAgeMillis(1000)
                                                         .build();
        final WebClient webClient = WebClient.builder(server.uri(sessionProtocol))
                                             .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(0L))
                                             .factory(clientFactory)
                                             .build();

        final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, "/request-timeout");
        final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request)
                                                                            .aggregate();

        final ServerMetrics serverMetrics = server.server()
                                                  .config()
                                                  .serverMetrics();
        await().until(() -> serverMetrics.pendingRequests() == 1);
        assertThat(serverMetrics.activeConnections()).isOne();
        request.close();

        final AggregatedHttpResponse result = response.join();

        // TODO: 2024/04/26 Need to change HttpStatus with SERVICE_UNAVAILABLE
        assertThat(result.status()).isSameAs(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(serverMetrics.pendingRequests()).isZero();
        assertThat(serverMetrics.activeRequests()).isZero();
        await().until(() -> serverMetrics.activeConnections() == 0);

        clientFactory.close();
    }
}
