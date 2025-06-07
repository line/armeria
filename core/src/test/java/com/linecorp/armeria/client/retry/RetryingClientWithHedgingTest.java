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

package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ResponseCancellationException;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingClientWithHedgingTest {
    private static final long LOOSING_SERVER_RESPONSE_DELAY_MILLIS = 300;

    private static final String SERVER1_RESPONSE = "s1";
    private static final String SERVER2_RESPONSE = "s2#";
    private static final String SERVER3_RESPONSE = "s3##";

    private static class TestServer extends ServerExtension {
        private CountDownLatch responseLatch = new CountDownLatch(1);
        private final AtomicInteger numRequests = new AtomicInteger();
        private HttpService helloService = mock(HttpService.class);

        TestServer() {
            super(true);
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.decorator(LoggingService.newDecorator());
            sb.service("/hello",
                       new HttpService() {
                           @Override
                           public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req)
                                   throws Exception {
                               numRequests.incrementAndGet();
                               try {
                                   responseLatch.await();
                               } catch (InterruptedException e) {
                                   fail(e);
                               }

                               req.whenComplete().handle((t, tt) -> {
                                   System.out.println("Request completed: " + req);
                                   return null;
                               });

                               final HttpResponse res = helloService.serve(ctx, req);

                               res.whenComplete().handle((t, tt) -> {
                                   System.out.println("Response completed: " + res);
                                   return null;
                               });

                               return res;
                           }

                           @Override
                           public ExchangeType exchangeType(RoutingContext routingContext) {
                               return ExchangeType.UNARY;
                           }
                       });
        }

        private void reset() {
            helloService = mock(HttpService.class);
            responseLatch = new CountDownLatch(1);
            numRequests.set(0);
        }

        public HttpService getHelloService() {
            return helloService;
        }

        public int getNumRequests() {
            return numRequests.get();
        }

        public void unlatchResponse() {
            responseLatch.countDown();
        }
    }

    @RegisterExtension
    private static final TestServer server1 = new TestServer();
    @RegisterExtension
    private static final TestServer server2 = new TestServer();
    @RegisterExtension
    private static final TestServer server3 = new TestServer();

    private static ClientFactory clientFactory;
    private final RetryRule NO_RETRY_RULE = RetryRule.builder().thenNoRetry();

    @BeforeAll
    static void beforeAll() {
        // use different eventLoop from server's so that clients don't hang when the eventLoop in server hangs
        clientFactory = ClientFactory.builder()
                                     .workerGroup(5).build();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    @BeforeEach
    void beforeEach() {
        server1.reset();
        server2.reset();
        server3.reset();
    }

    @AfterEach
    void afterEach() {
        server1.unlatchResponse();
        server2.unlatchResponse();
        server3.unlatchResponse();
    }

    @Test
    void letSecondServerWins() throws Exception {
        when(server1.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER1_RESPONSE));
        when(server2.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER2_RESPONSE));
        when(server3.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER3_RESPONSE));

        final RetryConfig<HttpResponse> hedgingNoRetryConfig = RetryConfig
                .builder(NO_RETRY_RULE)
                .maxTotalAttempts(3)
                .hedgingBackoff(Backoff.fixed(100))
                .build();

        final WebClient client = client(hedgingNoRetryConfig);

        final CompletableFuture<AggregatedHttpResponse> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = client.get("/hello").aggregate();
            ctx = captor.get();
        }

        await().untilAsserted(() -> {
            assertThat(server1.getNumRequests()).isOne();
            assertThat(server2.getNumRequests()).isOne();
            assertThat(server3.getNumRequests()).isOne();
        });

        server2.unlatchResponse();
        Thread.sleep(LOOSING_SERVER_RESPONSE_DELAY_MILLIS);
        server1.unlatchResponse();
        server3.unlatchResponse();

        await()
                .untilAsserted(() -> {
                    assertValidServerRequestContext(server1, 1);
                    assertValidServerRequestContext(server2, 2);
                    assertValidServerRequestContext(server3, 3);

                    assertValidAggregatedResponse(responseFuture, SERVER2_RESPONSE);
                    assertValidClientRequestContext(
                            ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER2_RESPONSE),
                            VERIFY_REQUEST_CANCELLED,
                            GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER2_RESPONSE),
                            VERIFY_REQUEST_CANCELLED
                    );
                });
    }

    @Test
    void letThirdServerWin() throws Exception {
        when(server1.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER1_RESPONSE));
        when(server2.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER2_RESPONSE));
        when(server3.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER3_RESPONSE));

        final RetryConfig<HttpResponse> hedgingNoRetryConfig = RetryConfig
                .builder(NO_RETRY_RULE)
                .maxTotalAttempts(3)
                .hedgingBackoff(Backoff.fixed(10))
                .build();

        final WebClient client = client(hedgingNoRetryConfig);

        final CompletableFuture<AggregatedHttpResponse> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = client.get("/hello").aggregate();
            ctx = captor.get();
        }

        await().untilAsserted(() -> {
            assertThat(server1.getNumRequests()).isOne();
            assertThat(server2.getNumRequests()).isOne();
            assertThat(server3.getNumRequests()).isOne();
        });

        server3.unlatchResponse();
        Thread.sleep(LOOSING_SERVER_RESPONSE_DELAY_MILLIS);
        server1.unlatchResponse();
        server2.unlatchResponse();

        await()
                .untilAsserted(() -> {
                    assertValidServerRequestContext(server1, 1);
                    assertValidServerRequestContext(server2, 2);
                    assertValidServerRequestContext(server3, 3);

                    assertValidAggregatedResponse(responseFuture, SERVER3_RESPONSE);
                    assertValidClientRequestContext(
                            ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE),
                            VERIFY_REQUEST_CANCELLED,
                            VERIFY_REQUEST_CANCELLED,
                            GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE)
                    );
                });
    }

    @Test
    void thirdWinsEvenAfterPerAttemptTimeout() throws Exception {
        when(server1.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER1_RESPONSE));
        when(server2.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER2_RESPONSE));
        when(server3.getHelloService().serve(any(), any())).thenReturn(HttpResponse.of(SERVER3_RESPONSE));

        final RetryConfig<HttpResponse> hedgingNoRetryConfig = RetryConfig
                .builder(RetryRule.builder().onTimeoutException().thenBackoff(Backoff.fixed(10_000))) // should
                // be always overtaken by hedging task
                .maxTotalAttempts(3)
                .responseTimeoutMillisForEachAttempt(100)
                .hedgingBackoff(Backoff.fixed(200))
                .build();

        final WebClient client = clientBuilder()
                .decorator(
                        RetryingClient.newDecorator(hedgingNoRetryConfig)
                )
                .build();

        final CompletableFuture<AggregatedHttpResponse> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = client.get("/hello").aggregate();
            ctx = captor.get();
        }

        await().pollInterval(25, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertThat(server1.getNumRequests()).isOne();
            assertThat(server2.getNumRequests()).isOne();
            assertThat(server3.getNumRequests()).isOne();
        });

        // Let the third server win
        server1.unlatchResponse(); // issued at T
        server2.unlatchResponse(); // issued at T + 200 (request 1 timed out)
        server3.unlatchResponse(); // issued at T + 400 (request 2 timed out)

        await()
                .untilAsserted(() -> {
                    assertValidServerRequestContext(server1, 1);
                    assertValidServerRequestContext(server2, 2);
                    assertValidServerRequestContext(server3, 3);

                    assertValidAggregatedResponse(responseFuture, SERVER3_RESPONSE);
                    assertValidClientRequestContext(
                            ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE),
                            VERIFY_REQUEST_TIMED_OUT,
                            VERIFY_REQUEST_TIMED_OUT, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE)
                    );
                });
    }

    @Test
    void thirdServerWinsEvenAfterRetriableResponse() throws Exception {
        when(server1.getHelloService().serve(any(), any()))
                .thenReturn(HttpResponse.of(HttpStatus.TOO_MANY_REQUESTS, MediaType.PLAIN_TEXT,
                                            SERVER1_RESPONSE));
        when(server2.getHelloService().serve(any(), any()))
                .thenReturn(HttpResponse.of(HttpStatus.TOO_MANY_REQUESTS, MediaType.PLAIN_TEXT,
                                            SERVER2_RESPONSE));
        when(server3.getHelloService().serve(any(), any()))
                .thenReturn(HttpResponse.of(SERVER3_RESPONSE));

        final RetryConfig<HttpResponse> config = RetryConfig
                .builder(RetryRule.of(
                        RetryRule
                                .builder()
                                .onStatus(HttpStatus.TOO_MANY_REQUESTS)
                                .thenBackoff(Backoff.withoutDelay()),
                        NO_RETRY_RULE
                ))
                .maxTotalAttempts(3)
                .hedgingBackoff(Backoff.fixed(200))
                .build();

        final WebClient client = client(config);

        final CompletableFuture<AggregatedHttpResponse> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = client.get("/hello").aggregate();
            ctx = captor.get();
        }

        server1.unlatchResponse();
        server2.unlatchResponse();
        server3.unlatchResponse();

        await().untilAsserted(() -> {
            assertThat(server1.getNumRequests()).isOne();
            assertThat(server2.getNumRequests()).isOne();
            assertThat(server3.getNumRequests()).isOne();
        });

        await().untilAsserted(() -> {
            assertValidServerRequestContext(server1, 1);
            assertValidServerRequestContext(server2, 2);
            assertValidServerRequestContext(server3, 3);

            assertValidAggregatedResponse(responseFuture, SERVER3_RESPONSE);
            assertValidClientRequestContext(
                    ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE),
                    GET_VERIFY_RESPONSE_HAS_CONTENT_AND_STATUS.apply(SERVER1_RESPONSE,
                                                                     HttpStatus.TOO_MANY_REQUESTS),
                    GET_VERIFY_RESPONSE_HAS_CONTENT_AND_STATUS.apply(SERVER2_RESPONSE,
                                                                     HttpStatus.TOO_MANY_REQUESTS),
                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE)
            );
        });
    }

    @Test
    void loosesAfterNonRetriableResponse() throws Exception {
        when(server1.getHelloService().serve(any(), any()))
                .thenReturn(HttpResponse.of(SERVER1_RESPONSE));
        when(server2.getHelloService().serve(any(), any()))
                .thenReturn(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT,
                                            SERVER2_RESPONSE));

        final RetryConfig<HttpResponse> config = RetryConfig
                .builder(NO_RETRY_RULE)
                .maxTotalAttempts(3)
                .hedgingBackoff(Backoff.fixed(100))
                .build();

        final WebClient client = client(config);

        final CompletableFuture<AggregatedHttpResponse> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = client.get("/hello").aggregate();
            ctx = captor.get();
        }

        server2.unlatchResponse();

        await().untilAsserted(() -> {
            assertThat(server2.getNumRequests()).isOne();
        });

        Thread.sleep(10);
        server1.unlatchResponse();
        server2.unlatchResponse();

        await().untilAsserted(() -> {
            assertValidServerRequestContext(server1, 1);
            assertValidServerRequestContext(server2, 2);
            assertNoServerRequestContext(server3);

            assertValidAggregatedResponse(responseFuture, HttpStatus.INTERNAL_SERVER_ERROR, SERVER2_RESPONSE);
            assertValidClientRequestContext(
                    ctx, GET_VERIFY_RESPONSE_HAS_CONTENT_AND_STATUS.apply(SERVER2_RESPONSE,
                                                                          HttpStatus.INTERNAL_SERVER_ERROR),
                    VERIFY_REQUEST_CANCELLED,
                    GET_VERIFY_RESPONSE_HAS_CONTENT_AND_STATUS.apply(SERVER2_RESPONSE,
                                                                     HttpStatus.INTERNAL_SERVER_ERROR), null
            );
        });
    }

    private static WebClientBuilder clientBuilder() {
        return WebClient.builder(SessionProtocol.H2C,
                                 EndpointGroup.of(EndpointSelectionStrategy.roundRobin(),
                                                  server1.httpEndpoint(),
                                                  server2.httpEndpoint(),
                                                  server3.httpEndpoint()))
                        .factory(clientFactory);
    }

    private static WebClient client(RetryConfig<HttpResponse> config) {
        return clientBuilder()
                .decorator(RetryingClient.newDecorator(config)).build();
    }

    private static String getResponseContent(AggregatedHttpResponse response) {
        return response.content().toString(Charset.defaultCharset());
    }

    private void assertValidAggregatedResponse(CompletableFuture<AggregatedHttpResponse> resFuture,
                                               String expectedContent) {
        assertThat(resFuture.isDone()).isTrue();
        final AggregatedHttpResponse res = resFuture.getNow(null);
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(getResponseContent(res)).isEqualTo(expectedContent);
    }

    private void assertValidAggregatedResponse(CompletableFuture<AggregatedHttpResponse> resFuture,
                                               HttpStatus expectedStatus, String expectedContent) {
        assertThat(resFuture.isDone()).isTrue();
        final AggregatedHttpResponse res = resFuture.getNow(null);
        assertThat(res.status()).isEqualTo(expectedStatus);
        assertThat(getResponseContent(res)).isEqualTo(expectedContent);
    }

    private void assertValidClientRequestContext(ClientRequestContext ctx,
                                                 RequestLogVerifier logVerifierCtx,
                                                 RequestLogVerifier logVerifierServer1,
                                                 RequestLogVerifier logVerifierServer2,
                                                 @Nullable RequestLogVerifier logVerifierServer3
    ) {
        assertThat(ctx.log().isComplete()).isTrue();
        assertThat(ctx.log().children()).hasSize(logVerifierServer3 == null ? 2 : 3);
        final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_CONTENT,
                                                        RequestLogProperty.RESPONSE_CAUSE,
                                                        RequestLogProperty.REQUEST_HEADERS);
        assertThat(log).isNotNull();
        logVerifierCtx.accept(log);
        assertValidChildLog(ctx.log().children().get(0), 1, logVerifierServer1);
        assertValidChildLog(ctx.log().children().get(1), 2, logVerifierServer2);
        if (logVerifierServer3 != null) {
            assertValidChildLog(ctx.log().children().get(2), 3, logVerifierServer3);
        }
    }

    void assertValidChildLog(RequestLogAccess logAccess, int attemptNumber,
                             RequestLogVerifier requestLogVerifier) {
        assertThat(logAccess.isComplete()).isTrue();
        // After the check right above, all properties of the RequestLog should be available.
        final @Nullable RequestLog log = logAccess.getIfAvailable(RequestLogProperty.RESPONSE_CONTENT,
                                                                  RequestLogProperty.RESPONSE_CAUSE,
                                                                  RequestLogProperty.REQUEST_HEADERS);
        assertThat(log).isNotNull();

        if (attemptNumber > 1) {
            assertThat(log.requestHeaders().getInt(ARMERIA_RETRY_COUNT)).isEqualTo(attemptNumber - 1);
        } else {
            assertThat(log.requestHeaders().contains(ARMERIA_RETRY_COUNT)).isFalse();
        }

        requestLogVerifier.accept(log);
    }

    private void assertValidServerRequestContext(ServerExtension server, int attemptNumber) {
        assertThat(server.requestContextCaptor().size()).isEqualTo(1);

        final ServiceRequestContext sctx = server.requestContextCaptor().peek();
        assertThat(sctx).isNotNull();
        assertThat(sctx.log().isComplete()).isTrue();

        final RequestLog slog = sctx.log().getIfAvailable(RequestLogProperty.REQUEST_HEADERS,
                                                          RequestLogProperty.REQUEST_CONTENT);

        assertThat(slog).isNotNull();
        if (attemptNumber > 1) {
            assertThat(slog.requestHeaders().getInt(ARMERIA_RETRY_COUNT)).isEqualTo(attemptNumber - 1);
        } else {
            assertThat(slog.requestHeaders().contains(ARMERIA_RETRY_COUNT)).isFalse();
        }

        assertThat(slog.requestHeaders().path()).contains("hello");
    }

    private void assertNoServerRequestContext(ServerExtension server) {
        assertThat(server.requestContextCaptor().size()).isEqualTo(0);
    }

    @FunctionalInterface
    private interface RequestLogVerifier extends Consumer<RequestLog> {}

    private static final RequestLogVerifier VERIFY_REQUEST_CANCELLED =
            log -> {
                assertThat(log.responseCause()).isInstanceOf(ResponseCancellationException.class);
            };

    private static final RequestLogVerifier VERIFY_REQUEST_TIMED_OUT =
            log -> {
                assertThat(log.responseCause()).isInstanceOf(ResponseTimeoutException.class);
            };
    //
//    private static final RequestLogVerifier VERIFY_RESPONSE_TIMEOUT =
//            log -> {
//                assertThat(log.responseCause()).isInstanceOf(TTransportException.class);
//                assertThat(log.responseCause().getCause()).isInstanceOf(ResponseTimeoutException.class);
//            };
//

    private static final BiFunction<String, HttpStatus, RequestLogVerifier>
            GET_VERIFY_RESPONSE_HAS_CONTENT_AND_STATUS =
            (expectedResponseContent, expectedStatus) -> log -> {
                assertThat(log.responseLength()).isEqualTo(expectedResponseContent.length());
                assertThat(log.responseStatus()).isEqualTo(expectedStatus);
            };

    private static final Function<String, RequestLogVerifier> GET_VERIFY_RESPONSE_HAS_CONTENT =
            expectedResponseContent -> GET_VERIFY_RESPONSE_HAS_CONTENT_AND_STATUS.apply(expectedResponseContent,
                                                                                        HttpStatus.OK);
}
