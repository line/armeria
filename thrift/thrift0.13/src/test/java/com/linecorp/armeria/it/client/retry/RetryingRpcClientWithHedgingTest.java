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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;
import testing.thrift.main.HelloService.Iface;

class RetryingRpcClientWithHedgingTest {
    private static final long LOOSING_SERVER_RESPONSE_DELAY_MILLIS = 50;

    private static class TestServer extends ServerExtension {
        private CountDownLatch responseLatch = new CountDownLatch(1);
        private CountDownLatch requestLatch = new CountDownLatch(1);
        private volatile HelloService.Iface serviceHandler;

        TestServer() {
            super(true);
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/thrift", THttpService.of((Iface) name -> getServiceHandler().hello(name))
                                              .decorate(
                                                      (delegate, ctx, req) -> {
                                                          getRequestLatch().countDown();
                                                          getResponseLatch().await();
                                                          return delegate.serve(ctx, req);
                                                      }
                                              )
                                              .decorate(LoggingService.newDecorator())
            );
        }

        private void reset() {
            serviceHandler = mock(HelloService.Iface.class);
            responseLatch = new CountDownLatch(1);
            requestLatch = new CountDownLatch(1);
        }

        public HelloService.Iface getServiceHandler() {
            return serviceHandler;
        }

        public CountDownLatch getResponseLatch() {
            return responseLatch;
        }

        public CountDownLatch getRequestLatch() {
            return requestLatch;
        }

        public void unlatchResponse() {
            responseLatch.countDown();
        }

        public void waitForFirstRequest() {
            try {
                requestLatch.await();
            } catch (InterruptedException e) {
                fail(e);
            }
        }
    }

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

    /*
        todo(szymon): Tests for hedging.
        todo(szymon): Sometimes returnErrorWhenSecondErrors blocks in a second iteration.
        Each test:
            - are connections closed on the client and server side?
            - are decorators before invoked with the right request context?
            - are decorators after invoked for every attempt? Are they invoked when we abort pending attempts?
            - the order and timing of the request send out (via client request contexts). Do we respect the
            per-attempt timeouts?
            - does a server receive a cancellation signal when it is lost?
        Test cases:
            Positive outcome:
            - First server wins, before the per attempt timeout
            - First server wins, after the per attempt timeout
            - Third server wins, before the per attempt timeout
            - Third server wins, after the per attempt timeout
            - First, second and third requests are issued in a row (Backoff.fixed(0)).
            - First, second and third requests are issued with backoff 0, 100, 0ms (non-monotonic).
            - First, second and third request each arrive earlier than the timeout.
            Negative outcome:
            - Request times out even before first server answers
            - Request times out shortly before third server, who should win, answers
            - First, second and third requests are issued; second request errors out; should abort every
            other request
            - Interaction with CircuitBreaker?
     */
    @Test
    void execute_hedging_lastWins() throws Exception {
        when(server1.getServiceHandler().hello(anyString())).thenReturn("server1");
        when(server2.getServiceHandler().hello(anyString())).thenReturn("server2");
        when(server3.getServiceHandler().hello(anyString())).thenReturn("server3");

        final HelloService.AsyncIface client = helloClientThreeEndpoints(
                RetryConfig.
                        builderForRpc(
                                RetryRule
                                        .builder()
                                        .onTimeoutException()
                                        .thenBackoff(Backoff.withoutDelay())
                        )
                        .maxTotalAttempts(3)
                        .responseTimeoutMillisForEachAttempt(50)
                        .hedgingDelayMillis(20)
                        .build()
        );

        final CompletableFuture<String> result;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            result = asyncHelloWith(client);
            ctx = captor.get();
        }

        server1.waitForFirstRequest();
        server2.waitForFirstRequest();
        server3.waitForFirstRequest();

        // Let server 3 win.
        server3.unlatchResponse();
        Thread.sleep(LOOSING_SERVER_RESPONSE_DELAY_MILLIS);
        server1.unlatchResponse();
        server2.unlatchResponse();

        await()
                .untilAsserted(() -> {
                    assertValidServerRequestContext(server1, 1);
                    assertValidServerRequestContext(server2, 2);
                    assertValidServerRequestContext(server3, 3);

                    assertThat(result.get()).isEqualTo("server3");
                    assertValidClientRequestContext(
                            ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply("server3"), VERIFY_REQUEST_CANCELLED,
                            VERIFY_REQUEST_CANCELLED,
                            GET_VERIFY_RESPONSE_HAS_CONTENT.apply("server3")
                    );
                });
    }

    @Test
    void execute_hedging_thirdWinsEventAfterPerAttemptTimeout() throws Exception {
        when(server1.getServiceHandler().hello(anyString())).thenReturn("server1");
        when(server2.getServiceHandler().hello(anyString())).thenReturn("server2");
        when(server3.getServiceHandler().hello(anyString())).thenReturn("server3");

        final HelloService.AsyncIface client = helloClientThreeEndpoints(
                RetryConfig.
                        builderForRpc(
                                RetryRule
                                        .builder()
                                        .onTimeoutException()
                                        .thenBackoff(Backoff.withoutDelay())
                        )
                        .maxTotalAttempts(3)
                        .responseTimeoutMillisForEachAttempt(50)
                        .hedgingDelayMillis(20)
                        .build()
        );

        final CompletableFuture<String> result;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            result = asyncHelloWith(client);
            ctx = captor.get();
        }

        // After 3 * <responseTimeoutMillisForEachAttempt>, we should have called the
        // per-attempt timeout handler a third time. It should not cancel
        // any request but should just wait for some request to finish (or for the request timeout).
        Thread.sleep(3 * 50 + 100);

        server1.waitForFirstRequest();
        server2.waitForFirstRequest();
        server3.waitForFirstRequest();

        // Let the third server win.
        server3.unlatchResponse();
        Thread.sleep(LOOSING_SERVER_RESPONSE_DELAY_MILLIS);
        server1.unlatchResponse();
        server2.unlatchResponse();

        await()
                .untilAsserted(() -> {
                    assertValidServerRequestContext(server1, 1);
                    assertValidServerRequestContext(server2, 2);
                    assertValidServerRequestContext(server3, 3);

                    assertThat(result.get()).isEqualTo("server3");
                    assertValidClientRequestContext(ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply("server3"),
                                                    VERIFY_REQUEST_CANCELLED, VERIFY_REQUEST_CANCELLED,
                                                    GET_VERIFY_RESPONSE_HAS_CONTENT.apply("server3"));
                });
    }

    @Test
    void execute_hedging_thirdWinsEvenWhenFirstErrors() throws Exception {
        final String errorMessage = "it's a me! non-retried error!";
        when(server1.getServiceHandler().hello(anyString())).thenThrow(
                new TApplicationException(TApplicationException.INTERNAL_ERROR, errorMessage));

        when(server2.getServiceHandler().hello(anyString())).thenReturn("server2");
        when(server3.getServiceHandler().hello(anyString())).thenReturn("server3");

        final HelloService.AsyncIface client = helloClientThreeEndpoints(
                RetryConfig.
                        builderForRpc(
                                RetryRule
                                        .builder()
                                        .onTimeoutException()
                                        .thenBackoff(Backoff.withoutDelay())
                        )
                        .maxTotalAttempts(3)
                        .responseTimeoutMillisForEachAttempt(1)
                        .hedgingDelayMillis(20)
                        .build()
        );

        final CompletableFuture<String> result;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            result = asyncHelloWith(client);
            ctx = captor.get();
        }

        server1.waitForFirstRequest();
        server2.waitForFirstRequest();
        server3.waitForFirstRequest();

        server3.unlatchResponse();
        Thread.sleep(LOOSING_SERVER_RESPONSE_DELAY_MILLIS);
        server1.unlatchResponse();
        server2.unlatchResponse();

        await().untilAsserted(() -> {
            assertValidServerRequestContext(server1, 1);
            assertValidServerRequestContext(server2, 2);
            assertValidServerRequestContext(server3, 3);

            assertThat(result.get()).isEqualTo("server3");
            assertValidClientRequestContext(
                    ctx, GET_VERIFY_RESPONSE_HAS_CONTENT.apply("server3"), VERIFY_REQUEST_CANCELLED,
                    VERIFY_REQUEST_CANCELLED, GET_VERIFY_RESPONSE_HAS_CONTENT.apply("server3")
            );
        });
    }

    @Test
    void execute_hedging_returnErrorWhenSecondErrors() throws Exception {
        when(server1.getServiceHandler().hello(anyString())).thenReturn("server1");
        final String errorMessage = "it's a me! non-retried error!";
        when(server2.getServiceHandler().hello(anyString())).thenThrow(
                new TApplicationException(TApplicationException.INTERNAL_ERROR, errorMessage));
        when(server3.getServiceHandler().hello(anyString())).thenReturn("server3");

        final HelloService.AsyncIface client = helloClientThreeEndpoints(
                RetryConfig.
                        builderForRpc(
                                RetryRule
                                        .builder()
                                        .onTimeoutException()
                                        .thenBackoff(Backoff.withoutDelay())
                        )
                        .maxTotalAttempts(3)
                        .responseTimeoutMillisForEachAttempt(1)
                        .hedgingDelayMillis(20)
                        .build()
        );

        final CompletableFuture<String> result;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            result = asyncHelloWith(client);
            ctx = captor.get();
        }

        server1.waitForFirstRequest();
        server2.waitForFirstRequest();
        server3.waitForFirstRequest();

        // Let the second server win.
        server2.unlatchResponse();
        Thread.sleep(LOOSING_SERVER_RESPONSE_DELAY_MILLIS);
        server1.unlatchResponse();
        server3.unlatchResponse();

        await().untilAsserted(() -> {
            assertValidServerRequestContext(server1, 1);
            assertValidServerRequestContext(server2, 2);
            assertValidServerRequestContext(server3, 3);

            assertThat(result)
                    .isCompletedExceptionally();

            assertThatExceptionOfType(ExecutionException.class)
                    .isThrownBy(result::get)
                    .satisfies(e ->
                                       assertThat(e.getCause())
                                               .isInstanceOf(TApplicationException.class)
                                               .hasMessageContaining(errorMessage)
                                               .satisfies(cause -> assertThat(
                                                       ((TApplicationException) cause).getType())
                                                       .isEqualTo(TApplicationException.INTERNAL_ERROR)));

            assertValidClientRequestContext(
                    ctx,
                    GET_VERIFY_RESPONSE_HAS_APPLICATION_EXCEPTION.apply(TApplicationException.INTERNAL_ERROR,
                                                                        errorMessage),
                    VERIFY_REQUEST_CANCELLED,
                    GET_VERIFY_RESPONSE_HAS_APPLICATION_EXCEPTION.apply(TApplicationException.INTERNAL_ERROR,
                                                                        errorMessage),
                    VERIFY_REQUEST_CANCELLED);
        });
    }

    @Test
    void execute_hedging_honorResponseTimeout() throws TException {
        when(server1.getServiceHandler().hello(anyString())).thenReturn("server1");
        when(server2.getServiceHandler().hello(anyString())).thenReturn("server2");

        final HelloService.AsyncIface client = helloClientThreeEndpoints(
                RetryConfig.
                        builderForRpc(
                                RetryRule
                                        .builder()
                                        .onTimeoutException()
                                        .thenBackoff(Backoff.withoutDelay())
                        )
                        .maxTotalAttempts(3)
                        .responseTimeoutMillisForEachAttempt(300)
                        .hedgingDelayMillis(20)
                        .build(), 300 + 100 // Lets give the client 100ms to schedule the second attempt.
        );

        final CompletableFuture<String> result;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            result = asyncHelloWith(client);
            ctx = captor.get();
        }
        // The first request is issued "immediately".
        // The second request issued after the per-attempt request timeout which is 300ms.
        // The third request would be issued after the per-attempt request timeout which is 300ms.
        // However, the request timeout is set to 400ms, so we should never call this.
        await().untilAsserted(() -> {
            assertThat(result)
                    .isCompletedExceptionally();

            assertThatExceptionOfType(ExecutionException.class)
                    .isThrownBy(result::get)
                    .satisfies(cause ->
                               {

                                   assertThat(cause.getCause()).isInstanceOf(TTransportException.class);
                                   assertThat(cause.getCause().getCause()).isInstanceOf(
                                           ResponseTimeoutException.class);
                               }
                    );

            // The first request timed out and the second was cancelled when it was detected.
            assertValidClientRequestContext(
                    ctx, VERIFY_RESPONSE_TIMEOUT, VERIFY_RESPONSE_TIMEOUT, VERIFY_REQUEST_CANCELLED, null);
        });

        server1.unlatchResponse();
        server2.unlatchResponse();
        server3.unlatchResponse();

        await().untilAsserted(() -> {
            assertValidServerRequestContext(server1, 1);
            assertValidServerRequestContext(server2, 2);
            assertNoServerRequestContext(server3);
        });

        await().pollDelay(1000, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertNoServerRequestContext(server3);
        });
    }

    private CompletableFuture<String> asyncHelloWith(HelloService.AsyncIface client) throws TException {
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

    private static HelloService.AsyncIface helloClientThreeEndpoints(RetryConfig<RpcResponse> config) {
        return helloClientThreeEndpoints(config, 10000);
    }

    private static HelloService.AsyncIface helloClientThreeEndpoints(RetryConfig<RpcResponse> config,
                                                                     long responseTimeoutMillis) {
        return ThriftClients.builder(
                                    SessionProtocol.HTTP,
                                    EndpointGroup.of(
                                            EndpointSelectionStrategy.roundRobin(),
                                            server1.endpoint(SessionProtocol.HTTP),
                                            server2.endpoint(SessionProtocol.HTTP),
                                            server3.endpoint(SessionProtocol.HTTP)
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

        final ServiceRequestContext sctx;

        try {
            sctx = server.requestContextCaptor().take();
        } catch (InterruptedException e) {
            fail(e);
            return;
        }

        assertThat(sctx.log().isComplete()).isTrue();

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
    }

    private void assertNoServerRequestContext(ServerExtension server) {
        assertThat(server.requestContextCaptor().size()).isEqualTo(0);
    }

}
