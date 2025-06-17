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
package com.linecorp.armeria.it.client.retry;

import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ResponseCancellationException;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;
import testing.thrift.main.HelloService.Iface;

class RetryingRpcClientWithHedgingTest {
    private static class TestServer extends ServerExtension {
        private CountDownLatch responseLatch = new CountDownLatch(1);
        private final AtomicInteger numRequests = new AtomicInteger();

        private volatile HelloService.Iface serviceHandler;

        TestServer() {
            super(true);
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.blockingTaskExecutor(5);
            sb.service("/thrift", THttpService.builder().useBlockingTaskExecutor(true).addService(
                                                      (Iface) name -> {
                                                          numRequests.incrementAndGet();
                                                          try {
                                                              responseLatch.await();
                                                          } catch (InterruptedException e) {
                                                              fail(e);
                                                          }

                                                          return getServiceHandler().hello(name);
                                                      }).build()
                                              .decorate(LoggingService.newDecorator())
            );
        }

        private void reset() {
            requestContextCaptor().clear();
            serviceHandler = mock(HelloService.Iface.class);
            responseLatch = new CountDownLatch(1);
            numRequests.set(0);
        }

        public HelloService.Iface getServiceHandler() {
            return serviceHandler;
        }

        public int getNumRequests() {
            return numRequests.get();
        }

        public void unlatchResponse() {
            responseLatch.countDown();
        }
    }

    private static final String RETRIABLE_RESPONSE = "please-retry-thanks";
    private static final String SERVER1_RESPONSE = "s1";
    private static final String SERVER2_RESPONSE = "s2#";
    private static final String SERVER3_RESPONSE = "s3##";

    private static final RetryRule NO_RETRY_RULE = RetryRule.builder().thenNoRetry();
    private static final RetryRuleWithContent<RpcResponse> RETRY_RETRIABLE_RESPONSES_RULE =
            RetryRuleWithContent
                    .<RpcResponse>builder()
                    .onResponse((ctx, response) -> {
                        return response.whenComplete().handle(
                                (responseData, cause) -> {
                                    assertThat(cause).isNull();
                                    assertThat(responseData).isInstanceOf(String.class);
                                    return responseData.equals(RETRIABLE_RESPONSE);
                                }
                        );
                    })
                    .thenBackoff(Backoff.withoutDelay());

    @RegisterExtension
    private static final TestServer server1 = new TestServer();
    @RegisterExtension
    private static final TestServer server2 = new TestServer();
    @RegisterExtension
    private static final TestServer server3 = new TestServer();

    @BeforeEach
    void beforeEach() {
        server1.reset();
        server2.reset();
        server3.reset();
    }

    @AfterEach
    void afterEach() {
        // Unblock all servers.
        server1.unlatchResponse();
        server2.unlatchResponse();
        server3.unlatchResponse();
    }

    @Test
    void firstServerWins() throws Exception {
        when(server1.getServiceHandler().hello(anyString())).thenReturn(SERVER1_RESPONSE);

        final HelloService.AsyncIface client = client(
                RetryConfig
                        .builderForRpc(NO_RETRY_RULE)
                        .maxTotalAttempts(3)
                        .hedgingDelayMillis(500)
                        .build()
        );

        final CompletableFuture<String> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = asyncHelloWith(client);
            ctx = captor.get();
        }

        // Let server 1 win.
        server1.unlatchResponse();

        await().untilAsserted(() -> {
            assertThat(responseFuture.get()).isEqualTo(SERVER1_RESPONSE);

            assertValidServerRequestContext(server1, 1, false);
            assertNoServerRequestContext(server2);
            assertNoServerRequestContext(server3);

            assertValidClientRequestContext(
                    ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER1_RESPONSE),
                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER1_RESPONSE),
                    null,
                    null
            );
        });
    }

    @Test
    void secondServerWins() throws Exception {
        when(server2.getServiceHandler().hello(anyString())).thenReturn(SERVER2_RESPONSE);

        final RetryConfig<RpcResponse> hedgingNoRetryConfig = RetryConfig
                .builderForRpc(NO_RETRY_RULE)
                .maxTotalAttempts(3)
                .hedgingDelayMillis(100)
                .build();

        final HelloService.AsyncIface client = client(hedgingNoRetryConfig);

        final CompletableFuture<String> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = asyncHelloWith(client);
            ctx = captor.get();
        }

        await().untilAsserted(() -> {
            assertThat(server1.getNumRequests()).isEqualTo(1);
            assertThat(server3.getNumRequests()).isEqualTo(1);
        });

        server2.unlatchResponse();

        await()
                .untilAsserted(() -> {
                    assertThat(responseFuture.get()).isEqualTo(SERVER2_RESPONSE);

                    assertValidClientRequestContext(
                            ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER2_RESPONSE),
                            VERIFY_REQUEST_CANCELLED,
                            GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER2_RESPONSE),
                            VERIFY_REQUEST_CANCELLED
                    );

                    assertValidServerRequestContext(server1, 1, true);
                    assertValidServerRequestContext(server2, 2, false);
                    assertValidServerRequestContext(server3, 3, true);
                });
    }

    @Test
    void thirdServerWins() throws Exception {
        when(server3.getServiceHandler().hello(anyString())).thenReturn(SERVER3_RESPONSE);

        final HelloService.AsyncIface client = client(
                RetryConfig
                        .builderForRpc(NO_RETRY_RULE)
                        .maxTotalAttempts(3)
                        .hedgingDelayMillis(10)
                        .build()
        );

        final CompletableFuture<String> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = asyncHelloWith(client);
            ctx = captor.get();
        }

        // Let server 3 win.
        server3.unlatchResponse();

        await().untilAsserted(() -> {
            assertThat(responseFuture.get()).isEqualTo(SERVER3_RESPONSE);

            assertValidServerRequestContext(server1, 1, true);
            assertValidServerRequestContext(server2, 2, true);
            assertValidServerRequestContext(server3, 3, false);

            assertValidClientRequestContext(
                    ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE),
                    VERIFY_REQUEST_CANCELLED,
                    VERIFY_REQUEST_CANCELLED,
                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE)
            );
        });
    }

    @Test
    void allServerLosePickLastResponse() {
        // todo(szymon): implement
    }

    @Test
    void thirdServerWinsEvenAfterPerAttemptTimeout() throws Exception {
        when(server3.getServiceHandler().hello(anyString())).thenReturn(SERVER3_RESPONSE);

        final RetryConfig<RpcResponse> hedgingNoRetryConfig = RetryConfig
                .builderForRpc(
                        RetryRule.builder().onTimeoutException().thenBackoff(Backoff.fixed(10_000))) // should
                // be always overtaken by hedging task
                .maxTotalAttempts(3)
                .responseTimeoutMillisForEachAttempt(100)
                .hedgingDelayMillis(200)
                .build();

        final HelloService.AsyncIface client = client(hedgingNoRetryConfig);

        final CompletableFuture<String> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = asyncHelloWith(client);
            ctx = captor.get();
        }

        await().pollInterval(25, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertThat(server3.getNumRequests()).isOne();
        });

        // As we know that the third server received a request, we know that
        // we are at >= T + 400. This means that:
        server1.unlatchResponse(); // issued at T (timed out)
        server2.unlatchResponse(); // issued at T + 200 (timed out)
        server3.unlatchResponse(); // issued at T + 400 (hopefully not timed out yet)

        await().untilAsserted(() -> {
            assertThat(responseFuture.get()).isEqualTo(SERVER3_RESPONSE);

            assertValidServerRequestContext(server1, 1, true);
            assertValidServerRequestContext(server2, 2, true);
            assertValidServerRequestContext(server3, 3, false);

            assertValidClientRequestContext(
                    ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE),
                    VERIFY_RESPONSE_TIMEOUT,
                    VERIFY_RESPONSE_TIMEOUT,
                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE)
            );
        });
    }

    @Test
    void thirdServerWinsEvenAfterRetriableResponse() throws Exception {
        when(server1.getServiceHandler().hello(anyString())).thenReturn(RETRIABLE_RESPONSE);
        when(server2.getServiceHandler().hello(anyString())).thenReturn(RETRIABLE_RESPONSE);
        when(server3.getServiceHandler().hello(anyString())).thenReturn(SERVER3_RESPONSE);

        final RetryConfig<RpcResponse> config = RetryConfig.builderForRpc(RETRY_RETRIABLE_RESPONSES_RULE)
                                                           .maxTotalAttempts(3)
                                                           .hedgingDelayMillis(10)
                                                           .build();

        final HelloService.AsyncIface client = client(config);

        final CompletableFuture<String> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = asyncHelloWith(client);
            ctx = captor.get();
        }

        server1.unlatchResponse();
        server2.unlatchResponse();
        server3.unlatchResponse();

        await().untilAsserted(() -> {
            assertThat(responseFuture.get()).isEqualTo(SERVER3_RESPONSE);

            assertValidServerRequestContext(server1, 1, false);
            assertValidServerRequestContext(server2, 2, false);
            assertValidServerRequestContext(server3, 3, false);

            assertValidClientRequestContext(
                    ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE),
                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply(RETRIABLE_RESPONSE),
                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply(RETRIABLE_RESPONSE),
                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply(SERVER3_RESPONSE)
            );
        });
    }

    @Test
    void loosesAfterNonRetriableResponse() throws TException {
        when(server2.getServiceHandler().hello(anyString())).thenThrow(new AnticipatedException("Aachen"));

        final RetryConfig<RpcResponse> config = RetryConfig
                .builderForRpc(NO_RETRY_RULE)
                .maxTotalAttempts(3)
                // Should be long enough so we can complete the second request before we continue issuing
                // a third request to the third server.
                .hedgingDelayMillis(500)
                .build();

        final HelloService.AsyncIface client = client(config);

        final CompletableFuture<String> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = asyncHelloWith(client);
            ctx = captor.get();
        }

        await().untilAsserted(() -> assertThat(server1.getNumRequests()).isEqualTo(1));

        server2.unlatchResponse();

        await().untilAsserted(() -> {
            assertThatThrownBy(responseFuture::get)
                    .satisfies(throwable -> {
                        final Throwable peeledThrowable = Exceptions.peel(throwable);
                        assertThat(peeledThrowable).isInstanceOf(TApplicationException.class);
                    });

            assertValidClientRequestContext(
                    ctx,
                    GET_VERIFY_RESPONSE_HAS_APPLICATION_EXCEPTION.apply(TApplicationException.INTERNAL_ERROR,
                                                                        "Aachen"),
                    VERIFY_REQUEST_CANCELLED,
                    GET_VERIFY_RESPONSE_HAS_APPLICATION_EXCEPTION.apply(TApplicationException.INTERNAL_ERROR,
                                                                        "Aachen"),
                    null
            );

            assertValidServerRequestContext(server1, 1, true);
            assertValidServerRequestContext(server2, 2, false, AnticipatedException.class);
            assertNoServerRequestContext(server3);
        });
    }

    @Test
    void loosesAfterResponseTimeout() {
        final RetryConfig<RpcResponse> config = RetryConfig
                .builderForRpc(NO_RETRY_RULE)
                .maxTotalAttempts(3)
                .hedgingDelayMillis(0)
                .build();

        final HelloService.AsyncIface client = client(config, 500);

        final CompletableFuture<String> responseFuture;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            responseFuture = asyncHelloWith(client);
            ctx = captor.get();
        }

        await().atLeast(400, TimeUnit.MILLISECONDS).atMost(600, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertThat(responseFuture).isCompletedExceptionally();
            assertThatThrownBy(responseFuture::get).satisfies(throwable -> {
                final Throwable peeledThrowable = Exceptions.peel(throwable);
                assertThat(peeledThrowable).isInstanceOf(TTransportException.class);
                assertThat(peeledThrowable.getCause()).isInstanceOf(ResponseTimeoutException.class);
            });
        });

        final List<Throwable> childLogExceptions = new ArrayList<>();

        final RequestLogVerifier catchException = log -> {
            assertThat(log.responseCause()).isInstanceOf(TTransportException.class);
            final TTransportException cause = (TTransportException) log.responseCause();
            assertThat(cause.getCause()).isNotNull();
            childLogExceptions.add(cause.getCause());
        };

        assertValidClientRequestContext(ctx,
                                        VERIFY_RESPONSE_TIMEOUT,
                                        catchException,
                                        catchException,
                                        catchException);

        int numTimeouts = 0;
        int numCancelled = 0;

        for (final @Nullable Throwable childException : childLogExceptions) {
            if (childException instanceof ResponseTimeoutException) {
                numTimeouts++;
            } else if (childException instanceof ResponseCancellationException) {
                numCancelled++;
            } else {
                fail("Unexpected exception: " + childException);
            }
        }

        assertThat(numTimeouts + numCancelled).isEqualTo(3);
        // At least one attempt needs to time out.
        assertThat(numTimeouts).isPositive();

        assertValidServerRequestContext(server1, 1, true);
        assertValidServerRequestContext(server2, 2, true);
        assertValidServerRequestContext(server3, 3, true);
    }

    private CompletableFuture<String> asyncHelloWith(HelloService.AsyncIface client) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        try {
            client.hello("hello", new AsyncMethodCallback<String>() {
                @Override
                public void onComplete(String response) {
                    future.complete(response);
                }

                @Override
                public void onError(Exception exception) {
                    future.completeExceptionally(exception);
                }
            });
        } catch (TException e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    private static HelloService.AsyncIface client(RetryConfig<RpcResponse> config) {
        return client(config, 10000);
    }

    private static HelloService.AsyncIface client(RetryConfig<RpcResponse> config,
                                                  long responseTimeoutMillis) {
        return ThriftClients.builder(
                                    SessionProtocol.H2C,
                                    EndpointGroup.of(
                                            EndpointSelectionStrategy.roundRobin(),
                                            server1.endpoint(SessionProtocol.H2C),
                                            server2.endpoint(SessionProtocol.H2C),
                                            server3.endpoint(SessionProtocol.H2C)
                                    )
                            )
                            .responseTimeoutMillis(responseTimeoutMillis)
                            .path("/thrift")
                            .rpcDecorator(RetryingRpcClient.builder(config).newDecorator())
                            .build(HelloService.AsyncIface.class);
    }

    private interface RequestLogVerifier extends Consumer<RequestLog> {}

    private static final RequestLogVerifier VERIFY_REQUEST_CANCELLED =
            log -> {
                assertThat(log.responseCause()).isInstanceOf(TTransportException.class);
                assertThat(log.responseCause().getCause()).isInstanceOf(ResponseCancellationException.class);
            };

    private static final RequestLogVerifier VERIFY_RESPONSE_TIMEOUT =
            log -> {
                assertThat(log.responseCause()).isInstanceOf(TTransportException.class);
                assertThat(log.responseCause().getCause()).isInstanceOf(ResponseTimeoutException.class);
            };

    private static final Function<String, RequestLogVerifier> GET_VERIFY_RESPONSE_HAS_CONTENT =
            expectedResponseContent -> log -> {
                assertThat(log.responseContent()).isInstanceOf(RpcResponse.class);
                assertThat((CompletionStage<Object>) log.responseContent())
                        .isCompletedWithValue(expectedResponseContent);
            };

    private static final BiFunction<Integer, String, RequestLogVerifier>
            GET_VERIFY_RESPONSE_HAS_APPLICATION_EXCEPTION =
            (expectedType, expectedMessage) -> log -> {
                assertThat(log.responseCause()).isInstanceOf(TApplicationException.class);
                final TApplicationException cause = (TApplicationException) log.responseCause();
                assertThat(cause.getType()).isEqualTo(expectedType);
                assertThat(cause.getMessage()).contains(expectedMessage);
            };

    private static void assertValidRootClientRequestContext(ClientRequestContext ctx,
                                                            RequestLogVerifier logVerifierCtx,
                                                            int expectedNumChildren) {
        assertThat(ctx.log().isComplete()).isTrue();
        assertThat(ctx.log().children()).hasSize(expectedNumChildren);
        final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_CONTENT,
                                                        RequestLogProperty.RESPONSE_CAUSE,
                                                        RequestLogProperty.REQUEST_HEADERS);
        assertThat(log).isNotNull();
        logVerifierCtx.accept(log);
    }

    private void assertValidClientRequestContext(ClientRequestContext ctx,
                                                 RequestLogVerifier logVerifierCtx,
                                                 RequestLogVerifier logVerifierServer1,
                                                 @Nullable RequestLogVerifier logVerifierServer2,
                                                 @Nullable RequestLogVerifier logVerifierServer3
    ) {
        final int expectedNumChildren = 1 + (logVerifierServer2 == null ? 0 : 1) +
                                        (logVerifierServer3 == null ? 0 : 1);

        assertValidRootClientRequestContext(ctx, logVerifierCtx, expectedNumChildren);
        assertValidChildLog(ctx.log().children().get(0), 1, logVerifierServer1);
        if (logVerifierServer2 != null) {
            assertValidChildLog(ctx.log().children().get(1), 2, logVerifierServer2);
        }

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

    private void assertValidServerRequestContext(ServerExtension server, int attemptNumber,
                                                 boolean expectCancelled) {
        assertValidServerRequestContext(server, attemptNumber, expectCancelled, null);
    }

    private void assertValidServerRequestContext(ServerExtension server, int attemptNumber,
                                                 boolean expectCancelled,
                                                 @Nullable Class<?> expectedResponseException) {
        assertThat(server.requestContextCaptor().size()).isEqualTo(1);

        final ServiceRequestContext sctx;
        sctx = server.requestContextCaptor().peek();
        assertThat(sctx).isNotNull();
        assertThat(sctx.log().isComplete()).isTrue();

        assertThat(sctx.isCancelled()).isEqualTo(expectCancelled);

        if (expectCancelled) {
            assertThat(sctx.cancellationCause()).isInstanceOf(ClosedStreamException.class);
            assertThat(sctx.cancellationCause().getMessage())
                    .contains("received a RST_STREAM frame: CANCEL");
        }

        final RequestLog slog = sctx.log().getIfAvailable(RequestLogProperty.REQUEST_HEADERS,
                                                          RequestLogProperty.REQUEST_CONTENT);

        assertThat(slog).isNotNull();
        if (attemptNumber > 1) {
            assertThat(slog.requestHeaders().getInt(ARMERIA_RETRY_COUNT)).isEqualTo(attemptNumber - 1);
        } else {
            assertThat(slog.requestHeaders().contains(ARMERIA_RETRY_COUNT)).isFalse();
        }

        assertThat(slog.requestContent()).isInstanceOf(RpcRequest.class);
        assertThat(((RpcRequest) slog.requestContent()).params().get(0)).isEqualTo("hello");

        if (expectedResponseException != null) {
            assertThat(slog.responseCause()).isInstanceOf(expectedResponseException);
        } else if (expectCancelled) {
            assertThat(slog.responseCause()).isInstanceOf(ClosedStreamException.class);
            assertThat(slog.responseCause().getMessage())
                    .contains("received a RST_STREAM frame: CANCEL");
        } else {
            assertThat(slog.responseCause()).isNull();
        }
    }

    private void assertNoServerRequestContext(ServerExtension server) {
        assertThat(server.requestContextCaptor().size()).isEqualTo(0);
    }
}
