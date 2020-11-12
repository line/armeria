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
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.TApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryConfigMapping;
import com.linecorp.armeria.client.retry.RetryDecision;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingRpcClientTest {

    private static final RetryRuleWithContent<RpcResponse> retryAlways =
            (ctx, response, cause) ->
                    CompletableFuture.completedFuture(RetryDecision.retry(Backoff.fixed(500)));

    private static final RetryRuleWithContent<RpcResponse> retryOnException =
            RetryRuleWithContent.<RpcResponse>builder().onException().thenBackoff(Backoff.withoutDelay());

    private final HelloService.Iface serviceHandler = mock(HelloService.Iface.class);
    private final DevNullService.Iface devNullServiceHandler = mock(DevNullService.Iface.class);
    private final AtomicInteger serviceRetryCount = new AtomicInteger();

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

        assertThat(client.hello("hello")).isEqualTo("world");
        verify(serviceHandler, only()).hello("hello");
    }

    @Test
    void execute_honorMapping() throws Exception {
        final HelloService.Iface client = helloClient(
                RetryConfigMapping.of(
                        (ctx, req) -> ctx.rpcRequest().params().contains("Alice") ?  "1" : "2",
                        (ctx, req) -> {
                            if (ctx.rpcRequest().params().contains("Alice")) {
                                return RetryConfig.<RpcResponse>builder(retryOnException)
                                        .maxTotalAttempts(3)
                                        .build();
                            } else {
                                return RetryConfig.<RpcResponse>builder(retryOnException)
                                        .maxTotalAttempts(5)
                                        .build();
                            }
                        }));

        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("Hey");
        serviceRetryCount.set(0);
        assertThat(client.hello("Alice")).isEqualTo("Hey");
        verify(serviceHandler, times(3)).hello("Alice");

        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("Hey");
        serviceRetryCount.set(0);
        assertThat(client.hello("Bob")).isEqualTo("Hey");
        verify(serviceHandler, times(5)).hello("Bob");
    }

    @Test
    void execute_retry() throws Exception {
        final HelloService.Iface client = helloClient(retryOnException, 100);
        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("world");

        assertThat(client.hello("hello")).isEqualTo("world");
        verify(serviceHandler, times(3)).hello("hello");
    }

    @Test
    void execute_reachedMaxAttempts() throws Exception {
        final HelloService.Iface client = helloClient(retryAlways, 2);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        final Throwable thrown = catchThrowable(() -> client.hello("hello"));
        assertThat(thrown).isInstanceOf(TApplicationException.class);
        assertThat(((TApplicationException) thrown).getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
        verify(serviceHandler, times(2)).hello("hello");
    }

    @Test
    void propagateLastResponseWhenNextRetryIsAfterTimeout() throws Exception {
        final BlockingQueue<RequestLog> logQueue = new LinkedTransferQueue<>();
        final RetryRuleWithContent<RpcResponse> rule =
                (ctx, response, cause) -> CompletableFuture.completedFuture(
                        RetryDecision.retry(Backoff.fixed(10000000)));
        final HelloService.Iface client = helloClient(rule, 100, logQueue);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());
        final Throwable thrown = catchThrowable(() -> client.hello("hello"));
        assertThat(thrown).isInstanceOf(TApplicationException.class);
        assertThat(((TApplicationException) thrown).getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
        verify(serviceHandler, only()).hello("hello");

        // Make sure the last HTTP request is set to the parent's HTTP request.
        final RequestLog log = logQueue.poll(10, TimeUnit.SECONDS);
        assertThat(log).isNotNull();
        assertThat(log.children()).isNotEmpty();
        final HttpRequest lastHttpReq = log.children().get(log.children().size() - 1).context().request();
        assertThat(lastHttpReq).isSameAs(log.context().request());
    }

    @Test
    void exceptionInStrategy() {
        final IllegalStateException exception = new IllegalStateException("foo");
        final HelloService.Iface client = helloClient((ctx, response, cause) -> {
            throw exception;
        }, Integer.MAX_VALUE);

        assertThatThrownBy(() -> client.hello("bar")).isSameAs(exception);
    }

    private HelloService.Iface helloClient(RetryConfigMapping<RpcResponse> mapping) {
        return Clients.builder(server.httpUri(BINARY) + "/thrift")
                      .rpcDecorator(RetryingRpcClient.newDecorator(mapping))
                      .build(HelloService.Iface.class);
    }

    private HelloService.Iface helloClient(RetryRuleWithContent<RpcResponse> rule, int maxAttempts) {
        return Clients.builder(server.httpUri(BINARY) + "/thrift")
                      .rpcDecorator(
                              RetryingRpcClient.builder(
                                      RetryConfig.builder(rule).maxTotalAttempts(maxAttempts).build())
                                               .newDecorator())
                      .build(HelloService.Iface.class);
    }

    private HelloService.Iface helloClient(RetryRuleWithContent<RpcResponse> rule, int maxAttempts,
                                           BlockingQueue<RequestLog> logQueue) {
        return Clients.builder(server.httpUri(BINARY) + "/thrift")
                      .rpcDecorator(RetryingRpcClient.builder(rule)
                                                     .maxTotalAttempts(maxAttempts)
                                                     .newDecorator())
                      .rpcDecorator((delegate, ctx, req) -> {
                          ctx.log().whenComplete().thenAccept(logQueue::add);
                          return delegate.execute(ctx, req);
                      })
                      .build(HelloService.Iface.class);
    }

    @Test
    void execute_void() throws Exception {
        final DevNullService.Iface client =
                Clients.builder(server.httpUri(BINARY) + "/thrift-devnull")
                       .rpcDecorator(RetryingRpcClient.newDecorator(retryOnException, 10))
                       .build(DevNullService.Iface.class);

        doThrow(new IllegalArgumentException())
                .doThrow(new IllegalArgumentException())
                .doNothing()
                .when(devNullServiceHandler).consume(anyString());
        client.consume("hello");
        verify(devNullServiceHandler, times(3)).consume("hello");
    }

    @Test
    void shouldGetExceptionWhenFactoryIsClosed() throws Exception {
        final ClientFactory factory =
                ClientFactory.builder().workerGroup(EventLoopGroups.newEventLoopGroup(2), true).build();

        final RetryRuleWithContent<RpcResponse> ruleWithContent =
                (ctx, response, cause) -> {
                    // Retry after 8000 which is slightly less than responseTimeoutMillis(10000).
                    return CompletableFuture.completedFuture(RetryDecision.retry(Backoff.fixed(8000)));
                };

        final HelloService.Iface client =
                Clients.builder(server.httpUri(BINARY) + "/thrift")
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
        Throwable t = catchThrowable(() -> client.hello("hello"));
        if (t instanceof UnprocessedRequestException) {
            final Throwable cause = t.getCause();
            assertThat(cause).isInstanceOf(IllegalStateException.class);
            t = cause;
        }
        assertThat(t).isInstanceOf(IllegalStateException.class)
                     .satisfies(cause -> assertThat(cause.getMessage()).matches(
                             "(?i).*(factory has been closed|not accepting a task).*"));
    }

    @Test
    void doNotRetryWhenResponseIsCancelled() throws Exception {
        final AtomicReference<ClientRequestContext> context = new AtomicReference<>();
        final HelloService.Iface client =
                Clients.builder(server.httpUri(BINARY) + "/thrift")
                       .rpcDecorator(RetryingRpcClient.builder(retryAlways)
                                                      .newDecorator())
                       .rpcDecorator((delegate, ctx, req) -> {
                           context.set(ctx);
                           final RpcResponse res = delegate.execute(ctx, req);
                           res.cancel(true);
                           return res;
                       })
                       .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        assertThatThrownBy(() -> client.hello("hello")).isInstanceOf(CancellationException.class);

        final RequestLog log = context.get().log().whenComplete().join();
        verify(serviceHandler, only()).hello("hello");
        assertThat(log.requestCause()).isNull();
        assertThat(log.responseCause()).isExactlyInstanceOf(CancellationException.class);

        // Sleep 1 second more to check if there was another retry.
        TimeUnit.SECONDS.sleep(1);
        verify(serviceHandler, only()).hello("hello");
    }
}
