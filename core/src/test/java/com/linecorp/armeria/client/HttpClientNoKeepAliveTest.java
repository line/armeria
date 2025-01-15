/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpClientNoKeepAliveTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0)
              .https(0)
              .tlsSelfSigned()
              // Disable idle timeout to check if "connection: close" closes the connection.
              .idleTimeoutMillis(0)
              .service("/", (ctx, req) -> {
                  return HttpResponse.delayed(HttpResponse.of("OK"), Duration.ofSeconds(1));
              });
        }
    };

    @CsvSource({ "20000", "0" })
    @ParameterizedTest
    void shouldCloseConnectionWhenNoPingAck(long idleTimeoutMillis) throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(idleTimeoutMillis)
                                                  .pingIntervalMillis(10000)
                                                  .useHttp1Pipelining(true)
                                                  .build()) {

            final WebClient client = WebClient.builder("h1c://127.0.0.1:" + ss.getLocalPort())
                                              .factory(factory)
                                              .decorator(MetricCollectingClient.newDecorator(
                                                      MeterIdPrefixFunction.ofDefault("client")))
                                              .build();
            client.get("/").aggregate();

            try (Socket s = ss.accept()) {
                final BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                final OutputStream out = s.getOutputStream();

                assertThat(in.readLine()).isEqualTo("GET / HTTP/1.1");
                assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                assertThat(in.readLine()).startsWith("user-agent: armeria/");
                assertThat(in.readLine()).isEmpty();
                out.write(("HTTP/1.1 200 OK\r\n" +
                           "Content-Length: 0\r\n" +
                           "\r\n").getBytes(StandardCharsets.US_ASCII));

                // No response for OPTIONS *
                assertThat(in.readLine()).isEqualTo("OPTIONS * HTTP/1.1");
                assertThat(in.readLine()).startsWith("user-agent: armeria/");
                assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                assertThat(in.readLine()).isEmpty();

                // Send another request before the PING timeout
                Thread.sleep(5000);
                client.get("/").aggregate();

                String line;
                while ((line = in.readLine()) != null) {
                    assertThat(line).doesNotContain("OPTIONS * HTTP/1.1");
                }
            }
        }
    }

    @EnumSource(value = SessionProtocol.class, mode = Mode.EXCLUDE, names = {"PROXY", "UNDEFINED"})
    @ParameterizedTest
    void shouldDisconnectWhenConnectionCloseHeaderIsIncluded(SessionProtocol protocol) {
        final CountingConnectionPoolListener countingPoolListener = new CountingConnectionPoolListener();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .connectionPoolListener(countingPoolListener)
                                  // Disable idle timeout to check if "connection: close" closes the connection.
                                  .tlsNoVerify()
                                  .idleTimeoutMillis(0)
                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(protocol))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final RequestHeaders headers =
                    RequestHeaders.builder(HttpMethod.GET, "/")
                                  .set(HttpHeaderNames.CONNECTION, "close")
                                  .build();
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response = client.execute(HttpRequest.of(headers));
                // Make sure that "Connection: close" is not stripped.
                // For HTTP/2, "Connection: close" is removed when it is converted to
                captor.get().log().whenComplete().join().requestHeaders()
                      .contains(HttpHeaderNames.CONNECTION, "close");
                assertThat(response.contentUtf8()).isEqualTo("OK");
            }
            await().untilAsserted(() -> {
                assertThat(countingPoolListener.closed()).isEqualTo(1);
            });
        }
    }

    @Test
    void shouldNotReuseConnectionWithNoKeepAliveWithHttp1() {
        final CountingConnectionPoolListener countingPoolListener = new CountingConnectionPoolListener();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .connectionPoolListener(countingPoolListener)
                                  .tlsNoVerify()
                                  // Disable idle timeout to check if "connection: close" closes the connection.
                                  .idleTimeoutMillis(0)
                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H1C))
                                              .factory(factory)
                                              .build();
            final int concurrency = 5;
            final List<CompletableFuture<ResponseEntity<String>>> futures =
                    IntStream.range(0, concurrency)
                             .mapToObj(unused -> client.prepare()
                                                       .get("/")
                                                       .header(HttpHeaderNames.CONNECTION, "close")
                                                       .asString()
                                                       .execute())
                             .collect(toImmutableList());
            CompletableFutures.allAsList(futures).join().forEach(response -> {
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.content()).isEqualTo("OK");
            });
            await().untilAsserted(() -> {
                assertThat(countingPoolListener.opened()).isEqualTo(concurrency);
                assertThat(countingPoolListener.closed()).isEqualTo(concurrency);
            });
        }
    }

    @Test
    void shouldNotReuseConnectionWithNoKeepAliveWithHttp2() {
        final CountingConnectionPoolListener countingPoolListener = new CountingConnectionPoolListener();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .connectionPoolListener(countingPoolListener)
                                  .tlsNoVerify()
                                  // Disable idle timeout to check if "connection: close" closes the connection.
                                  .idleTimeoutMillis(0)
                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H1C))
                                              .factory(factory)
                                              .build();
            final int concurrency = 5;
            final List<HttpResponse> responses = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                final RequestHeaders headers =
                        RequestHeaders.builder(HttpMethod.GET, "/")
                                      .set(HttpHeaderNames.CONNECTION, "close")
                                      .build();
                final HttpRequest httpRequest = HttpRequest.of(headers);
                responses.add(client.execute(httpRequest));
                // Wait for the request-side has completed. Inflight requests may use the same channel
                // until the request is subscribed. Once the headers are subscribed, the channel is removed
                // from the channel pool.
                httpRequest.whenComplete().join();
            }

            responses.stream().map(res -> res.aggregate().join()).forEach(response -> {
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("OK");
            });
            await().untilAsserted(() -> {
                assertThat(countingPoolListener.opened()).isEqualTo(concurrency);
                assertThat(countingPoolListener.closed()).isEqualTo(concurrency);
            });
        }
    }

    @Test
    void shouldAllowToUseConnectionForInflightRequests() {
        final WebClient client = server.webClient();
        final int concurrency = 20;
        final List<HttpResponse> responses = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final RequestHeaders headers =
                    RequestHeaders.builder(HttpMethod.GET, "/")
                                  .set(HttpHeaderNames.CONNECTION, "close")
                                  .build();
            final HttpRequest httpRequest = HttpRequest.of(headers);
            responses.add(client.execute(httpRequest));
        }

        // Make sure the all concurrent requests are completed successfully.
        responses.stream().map(res -> res.aggregate().join()).forEach(response -> {
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        });
    }
}
