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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.client.athenz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AthenzTokenLoadTimeoutTest {

    private static final BlockingQueue<CompletableFuture<HttpResponse>> responses = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> requests = new LinkedBlockingQueue<>();
    private static final TlsKeyPair keyPair = TlsKeyPair.ofSelfSigned();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.serviceUnder("/zts/v1", (ctx, req) -> {
                final CompletableFuture<HttpResponse> response = responses.remove();
                requests.add(req.path());
                return HttpResponse.of(response);
            });
            sb.service("/resource", (ctx, req) -> HttpResponse.of("ok"));
        }
    };

    @ParameterizedTest
    @CsvSource({ "false, false", "true, false", "false, true", "true, true" })
    void tokenClientTimeoutAndRetry(boolean roleToken, boolean preload) throws Exception {
        final CompletableFuture<HttpResponse> stalled = new CompletableFuture<>();
        responses.add(stalled);
        try (ZtsBaseClient zts = newZtsClient()) {
            final AthenzTokenClient client = AthenzTokenClient.builder(zts)
                    .domainName("testing")
                    .roleToken(roleToken)
                    .preload(preload)
                    .build();
            if (preload) {
                assertThat(requests.poll(10, TimeUnit.SECONDS)).isEqualTo(tokenPath(roleToken));
                responses.add(UnmodifiableFuture.completedFuture(tokenResponse(roleToken)));
                await().ignoreExceptions().untilAsserted(() -> {
                    assertThat(client.getToken().get(1, TimeUnit.SECONDS)).isEqualTo("test-token");
                });
            } else {
                final CompletableFuture<String> token = client.getToken();
                assertThat(requests.poll(10, TimeUnit.SECONDS)).isEqualTo(tokenPath(roleToken));
                assertThatThrownBy(() -> token.get(10, TimeUnit.SECONDS))
                        .satisfies(AthenzTokenLoadTimeoutTest::assertTimeout);
                responses.add(UnmodifiableFuture.completedFuture(tokenResponse(roleToken)));
                assertThat(client.getToken().get(10, TimeUnit.SECONDS)).isEqualTo("test-token");
            }
            assertThat(requests.poll(10, TimeUnit.SECONDS)).isEqualTo(tokenPath(roleToken));
        } finally {
            stalled.complete(tokenResponse(roleToken));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void decoratorTimeoutAndRetry(boolean roleToken) throws Exception {
        final CompletableFuture<HttpResponse> stalled = new CompletableFuture<>();
        responses.add(stalled);
        try (ZtsBaseClient zts = newZtsClient()) {
            final WebClient client = WebClient.builder(server.httpUri())
                    .decorator(AthenzClient.builder(zts)
                            .domainName("testing")
                            .tokenType(roleToken ? TokenType.YAHOO_ROLE_TOKEN : TokenType.ACCESS_TOKEN)
                            .newDecorator())
                    .build();
            final CompletableFuture<?> response = client.get("/resource").aggregate();
            assertThat(requests.poll(10, TimeUnit.SECONDS)).isEqualTo(tokenPath(roleToken));
            assertThatThrownBy(() -> response.get(10, TimeUnit.SECONDS))
                    .satisfies(AthenzTokenLoadTimeoutTest::assertTimeout);

            responses.add(UnmodifiableFuture.completedFuture(tokenResponse(roleToken)));
            assertThat(client.get("/resource").aggregate().get(10, TimeUnit.SECONDS).contentUtf8())
                    .isEqualTo("ok");
            assertThat(requests.poll(10, TimeUnit.SECONDS)).isEqualTo(tokenPath(roleToken));
        } finally {
            stalled.complete(tokenResponse(roleToken));
        }
    }

    @ParameterizedTest
    @CsvSource({ "false, 0", "true, 0", "false, 1000", "true, 1000",
                 "false, 10000", "true, 10000" })
    void preservesHttpTimeout(boolean roleToken, long httpTimeoutMillis) throws Exception {
        final CompletableFuture<Long> requestTimeout = new CompletableFuture<>();
        responses.add(UnmodifiableFuture.completedFuture(tokenResponse(roleToken)));
        try (ZtsBaseClient zts = ZtsBaseClient.builder(server.httpUri())
                .keyPair(() -> keyPair)
                .configureWebClient(builder -> builder.responseTimeoutMillis(httpTimeoutMillis)
                        .decorator((delegate, ctx, req) -> {
                            requestTimeout.complete(ctx.responseTimeoutMillis());
                            return delegate.execute(ctx, req);
                        }))
                .build()) {
            final AthenzTokenClient client = AthenzTokenClient.builder(zts)
                    .domainName("testing")
                    .roleToken(roleToken)
                    .build();
            assertThat(client.getToken().get(10, TimeUnit.SECONDS)).isEqualTo("test-token");
            assertThat(requestTimeout.get(10, TimeUnit.SECONDS)).isEqualTo(httpTimeoutMillis);
            assertThat(zts.webClient().options().responseTimeoutMillis()).isEqualTo(httpTimeoutMillis);
            assertThat(requests.poll(10, TimeUnit.SECONDS)).isEqualTo(tokenPath(roleToken));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancelsTimedOutHttpRequest(boolean roleToken) throws Exception {
        final CompletableFuture<HttpResponse> stalled = new CompletableFuture<>();
        final CompletableFuture<HttpResponse> httpResponse = new CompletableFuture<>();
        responses.add(stalled);
        try (ZtsBaseClient zts = ZtsBaseClient.builder(server.httpUri())
                .keyPair(() -> keyPair)
                .configureWebClient(builder -> builder.responseTimeoutMillis(200)
                        .decorator((delegate, ctx, req) -> {
                            final HttpResponse response = delegate.execute(ctx, req);
                            httpResponse.complete(response);
                            return response;
                        }))
                .build()) {
            final AthenzTokenClient client = AthenzTokenClient.builder(zts)
                    .domainName("testing")
                    .roleToken(roleToken)
                    .build();
            final CompletableFuture<String> token = client.getToken();
            assertThat(requests.poll(10, TimeUnit.SECONDS)).isEqualTo(tokenPath(roleToken));
            assertThatThrownBy(() -> token.get(10, TimeUnit.SECONDS))
                    .satisfies(AthenzTokenLoadTimeoutTest::assertTimeout);
            assertThatThrownBy(() -> httpResponse.get(10, TimeUnit.SECONDS)
                                                .whenComplete().get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ResponseTimeoutException.class);
        } finally {
            stalled.complete(tokenResponse(roleToken));
        }
    }

    @ParameterizedTest
    @CsvSource({ "0, 3000", "200, 3200", "10000, 13000",
                 "9223372036854775807, 9223372036854775807" })
    void derivesLoaderTimeout(long responseTimeoutMillis, long expectedLoadTimeoutMillis) {
        try (ZtsBaseClient zts = ZtsBaseClient.builder(server.httpUri())
                .keyPair(() -> keyPair)
                .configureWebClient(builder -> builder.responseTimeoutMillis(responseTimeoutMillis))
                .build()) {
            assertThat(AthenzTokenClientBuilder.tokenLoadTimeoutMillis(zts.webClient()))
                    .isEqualTo(expectedLoadTimeoutMillis);
        }
    }

    @ParameterizedTest
    @CsvSource({ "false, 0", "true, 0", "false, 200", "true, 200" })
    void stalledLoadTimesOutAfterHttpTimeoutBuffer(boolean roleToken, long responseTimeoutMillis)
            throws Exception {
        final CompletableFuture<HttpResponse> stalled = new CompletableFuture<>();
        final AtomicInteger attempts = new AtomicInteger();
        try (ZtsBaseClient zts = ZtsBaseClient.builder(server.httpUri())
                .keyPair(() -> keyPair)
                .configureWebClient(builder -> builder.responseTimeoutMillis(responseTimeoutMillis)
                        .decorator((delegate, ctx, req) -> {
                            if (attempts.incrementAndGet() == 1) {
                                return HttpResponse.of(stalled);
                            }
                            return tokenResponse(roleToken);
                        }))
                .build()) {
            final AthenzTokenClient client = AthenzTokenClient.builder(zts)
                    .domainName("testing")
                    .roleToken(roleToken)
                    .build();
            final long startNanos = System.nanoTime();
            final CompletableFuture<String> token = client.getToken();
            assertThatThrownBy(() -> token.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(TimeoutException.class);
            assertThat(System.nanoTime() - startNanos)
                    .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis + 3000));
            assertThat(client.getToken().get(10, TimeUnit.SECONDS)).isEqualTo("test-token");
            assertThat(attempts).hasValue(2);
        } finally {
            stalled.complete(tokenResponse(roleToken));
        }
    }

    private static void assertTimeout(Throwable cause) {
        assertThat(Throwables.getRootCause(cause)).isInstanceOf(ResponseTimeoutException.class);
    }

    private static ZtsBaseClient newZtsClient() {
        return ZtsBaseClient.builder(server.httpUri())
                .keyPair(() -> keyPair)
                .configureWebClient(builder -> builder.responseTimeoutMillis(200))
                .build();
    }

    private static String tokenPath(boolean roleToken) {
        return roleToken ? "/zts/v1/domain/testing/token" : "/zts/v1/oauth2/token";
    }

    private static HttpResponse tokenResponse(boolean roleToken) {
        if (roleToken) {
            return HttpResponse.ofJson(ImmutableMap.of("token", "test-token", "expiryTime",
                                                       Instant.now().plusSeconds(3600).getEpochSecond()));
        }
        return HttpResponse.ofJson(ImmutableMap.of("access_token", "test-token", "token_type", "Bearer",
                                                   "expires_in", 3600));
    }
}
