/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.it.client.retry;

import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static com.linecorp.armeria.internal.testing.RequestContextUtils.assertValidRequestContext;
import static com.linecorp.armeria.internal.testing.RequestContextUtils.assertValidRequestContextWithParentLogVerifier;
import static com.linecorp.armeria.internal.testing.RequestContextUtils.verifyAllVerifierValid;
import static com.linecorp.armeria.internal.testing.RequestContextUtils.verifyRequestCause;
import static com.linecorp.armeria.internal.testing.RequestContextUtils.verifyResponseCause;
import static com.linecorp.armeria.internal.testing.RequestContextUtils.verifyStatusCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.TApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryConfigMapping;
import com.linecorp.armeria.client.retry.RetryDecision;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.CompletableRpcResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.testing.RequestContextUtils.RequestLogVerifier;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.DevNullService;
import testing.thrift.main.HelloService;

class RetryingRpcClientTest {

    private static final RetryRuleWithContent<RpcResponse> retryAlways =
            (ctx, response, cause) ->
                    UnmodifiableFuture.completedFuture(RetryDecision.retry(Backoff.fixed(500)));

    private static final RetryRuleWithContent<RpcResponse> retryOnException =
            RetryRuleWithContent.onException(Backoff.withoutDelay());

    private final HelloService.Iface serviceHandler = mock(HelloService.Iface.class);
    private final DevNullService.Iface devNullServiceHandler = mock(DevNullService.Iface.class);
    private final AtomicInteger serviceRetryCount = new AtomicInteger();

    private ClientRequestContext ctx;

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            serviceRetryCount.set(0);
            sb.service("/thrift", THttpService.of(serviceHandler).decorate(
                    (delegate, ctx, req) -> {
                        final int count = serviceRetryCount.getAndIncrement();
                        if (count != 0) {
                            assertThat(count).isEqualTo(req.headers().getInt(ARMERIA_RETRY_COUNT));
                        }
                        return delegate.serve(ctx, req);
                    }));
            sb.service("/thrift-devnull", THttpService.of(devNullServiceHandler));
        }
    };

    @Test
    void execute() throws Exception {
        final HelloService.Iface client = helloClient(retryOnException, 100);

        when(serviceHandler.hello(anyString())).thenReturn("world");

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.hello("hello")).isEqualTo("world");
            ctx = captor.get();
        }
        verify(serviceHandler, only()).hello("hello");
        awaitValidClientRequestContext(ctx, verifyResponse("world"));
    }

    @Test
    void execute_honorMapping() throws Exception {
        final HelloService.Iface client = helloClient(
                RetryConfigMapping.of(
                        (ctx, req) -> ctx.rpcRequest().params().contains("Alice") ? "1" : "2",
                        (ctx, req) -> {
                            if (ctx.rpcRequest().params().contains("Alice")) {
                                return RetryConfig.builderForRpc(retryOnException)
                                                  .maxTotalAttempts(3)
                                                  .build();
                            } else {
                                return RetryConfig.builderForRpc(retryOnException)
                                                  .maxTotalAttempts(5)
                                                  .build();
                            }
                        }));

        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("Hey");
        serviceRetryCount.set(0);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.hello("Alice")).isEqualTo("Hey");
            ctx = captor.get();
        }
        verify(serviceHandler, times(3)).hello("Alice");
        awaitValidClientRequestContext(ctx,
                                       verifyResponseException(),
                                       verifyResponseException(),
                                       verifyResponse("Hey"));

        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("Hey");
        serviceRetryCount.set(0);
        assertThat(client.hello("Bob")).isEqualTo("Hey");
        verify(serviceHandler, times(5)).hello("Bob");
        awaitValidClientRequestContext(ctx,
                                       verifyResponseException(),
                                       verifyResponseException(),
                                       verifyResponse("Hey"));
    }

    @Test
    void evaluatesMappingOnce() throws Exception {
        final AtomicInteger evaluations = new AtomicInteger(0);
        final HelloService.Iface client = helloClient(
                (ctx, req) -> {
                    evaluations.incrementAndGet();
                    return RetryConfig
                            .builderForRpc(retryOnException)
                            .maxTotalAttempts(3)
                            .build();
                }
        );

        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("Hey");

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.hello("Alice")).isEqualTo("Hey");
            ctx = captor.get();
        }
        // 1 logical request; 3 retries
        assertThat(evaluations.get()).isEqualTo(1);
        verify(serviceHandler, times(3)).hello("Alice");
        awaitValidClientRequestContext(ctx, verifyResponseException(), verifyResponseException(),
                                       verifyResponse("Hey"));

        serviceRetryCount.set(0);

        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("Hey");

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.hello("Alice")).isEqualTo("Hey");
            ctx = captor.get();
        }
        // 2 logical requests total; 6 retries total
        assertThat(evaluations.get()).isEqualTo(2);
        verify(serviceHandler, times(6)).hello("Alice");
        awaitValidClientRequestContext(ctx, verifyResponseException(), verifyResponseException(),
                                       verifyResponse("Hey"));
    }

    @Test
    void execute_retry() throws Exception {
        final HelloService.Iface client = helloClient(retryOnException, 100);
        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("world");

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.hello("hello")).isEqualTo("world");
            ctx = captor.get();
        }
        verify(serviceHandler, times(3)).hello("hello");
        awaitValidClientRequestContext(ctx, verifyResponseException(),
                                       verifyResponseException(), verifyResponse("world"));
    }

    @Test
    void execute_reachedMaxAttempts() throws Exception {
        final HelloService.Iface client = helloClient(retryAlways, 2);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        final Throwable thrown;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            thrown = catchThrowable(() -> client.hello("hello"));
            ctx = captor.get();
        }
        assertThat(thrown).isInstanceOf(TApplicationException.class);
        assertThat(((TApplicationException) thrown).getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
        verify(serviceHandler, times(2)).hello("hello");
        awaitValidClientRequestContext(ctx, verifyResponseException(), verifyResponseException());
    }

    @Test
    void propagateLastResponseWhenNextRetryIsAfterTimeout() throws Exception {
        final RetryRuleWithContent<RpcResponse> rule =
                (ctx, response, cause) -> UnmodifiableFuture.completedFuture(
                        RetryDecision.retry(Backoff.fixed(10000000)));
        final HelloService.Iface client = helloClient(rule, 100);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());
        final Throwable thrown;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            thrown = catchThrowable(() -> client.hello("hello"));
            ctx = captor.get();
        }
        assertThat(thrown).isInstanceOf(TApplicationException.class);
        assertThat(((TApplicationException) thrown).getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
        verify(serviceHandler, only()).hello("hello");
        awaitValidClientRequestContext(ctx, verifyResponseException());
    }

    @Test
    void exceptionInStrategy() {
        final IllegalStateException exception = new IllegalStateException("foo");
        final HelloService.Iface client = helloClient((ctx, response, cause) -> {
            throw exception;
        }, Integer.MAX_VALUE);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> client.hello("bar")).isSameAs(exception);
            ctx = captor.get();
        }

        awaitValidRequestContextWithParentLogVerifier(ctx,
                                                      verifyAllVerifierValid(
                                                              // Not a response exception from the server so
                                                              // we are not using verifyResponseCause
                                                              verifyStatusCode(HttpStatus.UNKNOWN),
                                                              verifyResponseCause(exception)
                                                      ),
                                                      verifyResponseException(
                                                              TApplicationException.MISSING_RESULT));
    }

    private HelloService.Iface helloClient(RetryConfigMapping<RpcResponse> mapping) {
        return ThriftClients.builder(server.httpUri())
                            .path("/thrift")
                            .rpcDecorator(RetryingRpcClient.newDecorator(mapping))
                            .build(HelloService.Iface.class);
    }

    private HelloService.Iface helloClient(RetryRuleWithContent<RpcResponse> rule, int maxAttempts) {
        return ThriftClients.builder(server.httpUri())
                            .path("/thrift")
                            .rpcDecorator(
                                    RetryingRpcClient.builder(RetryConfig.builderForRpc(rule)
                                                                         .maxTotalAttempts(maxAttempts)
                                                                         .build())
                                                     .newDecorator())
                            .build(HelloService.Iface.class);
    }

    @Test
    void execute_void() throws Exception {
        final DevNullService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/thrift-devnull")
                             .rpcDecorator(RetryingRpcClient.builder(retryOnException)
                                                            .maxTotalAttempts(10)
                                                            .newDecorator())
                             .build(DevNullService.Iface.class);

        doThrow(new IllegalArgumentException())
                .doThrow(new IllegalArgumentException())
                .doNothing()
                .when(devNullServiceHandler).consume(anyString());
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.consume("hello");
            ctx = captor.get();
        }
        verify(devNullServiceHandler, times(3)).consume("hello");
        awaitValidClientRequestContext(ctx, verifyResponseException(),
                                       verifyResponseException(), verifyResponse(null)
        );
    }

    @Test
    void shouldGetExceptionWhenFactoryIsClosed() throws Exception {
        final ClientFactory factory =
                ClientFactory.builder().workerGroup(2).build();

        final RetryRuleWithContent<RpcResponse> ruleWithContent =
                (ctx, response, cause) -> {
                    // Retry after 8000 which is slightly less than responseTimeoutMillis(10000).
                    return UnmodifiableFuture.completedFuture(RetryDecision.retry(Backoff.fixed(8000)));
                };

        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/thrift")
                             .responseTimeoutMillis(10000)
                             .factory(factory)
                             .rpcDecorator(RetryingRpcClient.builder(ruleWithContent)
                                                            .newDecorator())
                             .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        // There's no way to notice that the RetryingClient has scheduled the next retry.
        // The next retry will be after 8 seconds so closing the factory after 3 seconds should work.
        Executors.newSingleThreadScheduledExecutor().schedule(factory::close, 3, TimeUnit.SECONDS);

        // But it turned out that it's not working as expected in certain circumstance,
        // so we should handle all the cases.
        //
        // 1 - In RetryingClient, IllegalStateException("ClientFactory has been closed.") can be raised.
        // 2 - In HttpChannelPool, BootStrap.connect() can raise
        //     IllegalStateException("executor not accepting a task") wrapped by UnprocessedRequestException.
        // 3 - In HttpClientDelegate, addressResolverGroup.getResolver(eventLoop) can raise
        //     IllegalStateException("executor not accepting a task").
        //
        Throwable t;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            t = catchThrowable(() -> client.hello("hello"));
            ctx = captor.get();
        }
        if (t instanceof UnprocessedRequestException) {
            final Throwable cause = t.getCause();
            assertThat(cause).isInstanceOf(IllegalStateException.class);
            t = cause;
            awaitValidClientRequestContext(ctx, verifyAllVerifierValid(
                    // We cannot be sure that we set
                    // the request cause so we are not checking
                    // with verifyRequestException/
                    // verifyRequestCause().
                    verifyStatusCode(HttpStatus.UNKNOWN),
                    verifyResponseCause(t)
            ));
        } else {
            awaitValidRequestContextWithParentLogVerifier(ctx,
                                                          verifyAllVerifierValid(
                                                                  // Same as above.
                                                                  verifyStatusCode(HttpStatus.UNKNOWN),
                                                                  verifyResponseCause(t)
                                                          ),
                                                          verifyResponseException(
                                                                  TApplicationException.INTERNAL_ERROR)
            );
        }
        assertThat(t).isInstanceOf(IllegalStateException.class)
                     .satisfies(cause -> assertThat(cause.getMessage()).matches(
                             "(?i).*(factory has been closed|not accepting a task).*"));
    }

    @Test
    void doNotRetryWhenResponseIsCancelled() throws Exception {
        final AtomicReference<ClientRequestContext> context = new AtomicReference<>();
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .path("/thrift")
                             .rpcDecorator(RetryingRpcClient.builder(retryAlways).newDecorator())
                             .rpcDecorator((delegate, ctx, req) -> {
                                 context.set(ctx);
                                 final RpcResponse res = delegate.execute(ctx, req);
                                 res.cancel(true);
                                 return res;
                             })
                             .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> client.hello("hello")).isInstanceOf(CancellationException.class);
            ctx = captor.get();
        }

        await().untilAsserted(() -> {
            verify(serviceHandler, only()).hello("hello");
        });
        // Sleep 1 second more to check if there was another retry.
        TimeUnit.SECONDS.sleep(1);
        verify(serviceHandler, only()).hello("hello");
        assertValidRequestContextWithParentLogVerifier(
                ctx,
                // ClientUtil.completeLogIfIncomplete() records exceptions caused by response cancellations.
                verifyRequestException(CancellationException.class),
                verifyResponseException(TApplicationException.INTERNAL_ERROR));
    }

    private static void awaitValidClientRequestContext(ClientRequestContext ctx,
                                                       RequestLogVerifier... childLogVerifiers) {
        await().untilAsserted(() -> assertValidRequestContext(ctx, childLogVerifiers));
    }

    private static void awaitValidRequestContextWithParentLogVerifier(ClientRequestContext ctx,
                                                                      RequestLogVerifier parentLogVerifier,
                                                                      RequestLogVerifier... childLogVerifiers) {
        await().untilAsserted(() -> assertValidRequestContextWithParentLogVerifier(
                ctx, parentLogVerifier, childLogVerifiers));
    }

    private static RequestLogVerifier verifyRequestException(Class<?> causeClass) {
        return verifyAllVerifierValid(
                verifyStatusCode(HttpStatus.UNKNOWN),
                verifyRequestCause(causeClass),
                verifyResponseCause(causeClass)
        );
    }

    private static RequestLogVerifier verifyResponseException() {
        return verifyResponseException(TApplicationException.INTERNAL_ERROR);
    }

    private static RequestLogVerifier verifyResponseException(int type) {
        return verifyAllVerifierValid(
                verifyStatusCode(HttpStatus.OK),
                verifyResponseCause(TApplicationException.class),
                childLog -> {
                    final TApplicationException responseCause =
                            (TApplicationException) childLog.responseCause();

                    assertThat(responseCause.getType()).isEqualTo(type);
                }
        );
    }

    private static RequestLogVerifier verifyResponse(@Nullable String expectedResponse) {
        return verifyAllVerifierValid(
                verifyStatusCode(HttpStatus.OK),
                childLog -> {
                    assertThat(childLog.responseContent()).isExactlyInstanceOf(CompletableRpcResponse.class);
                    final CompletableRpcResponse rpcResponse =
                            (CompletableRpcResponse) childLog.responseContent();
                    assertThat(rpcResponse.isDone()).isTrue();
                    assertThat(rpcResponse.isCompletedExceptionally()).isFalse();
                    assertThat(rpcResponse.getNow("should-not-be-returned")).isEqualTo(expectedResponse);
                }
        );
    }
}
