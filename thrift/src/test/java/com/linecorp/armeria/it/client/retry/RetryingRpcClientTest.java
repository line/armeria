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

import static com.linecorp.armeria.client.retry.RetryingClient.ARMERIA_RETRY_COUNT;
import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TApplicationException;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryStrategyWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.client.retry.RetryingRpcClientBuilder;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.server.ServerRule;

public class RetryingRpcClientTest {

    private static final RetryStrategyWithContent<RpcResponse> retryAlways =
            (ctx, response) -> CompletableFuture.completedFuture(Backoff.fixed(500));

    private static final RetryStrategyWithContent<RpcResponse> retryOnException =
            (ctx, response) -> response.completionFuture().handle((unused, cause) -> {
                if (cause != null) {
                    return Backoff.withoutDelay();
                }
                return null;
            });

    private final HelloService.Iface serviceHandler = mock(HelloService.Iface.class);
    private final DevNullService.Iface devNullServiceHandler = mock(DevNullService.Iface.class);

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final AtomicInteger retryCount = new AtomicInteger();
            sb.service("/thrift", THttpService.of(serviceHandler).decorate(
                    (delegate, ctx, req) -> {
                        final int count = retryCount.getAndIncrement();
                        if (count != 0) {
                            assertThat(count).isEqualTo(req.headers().getInt(ARMERIA_RETRY_COUNT));
                        }
                        return delegate.serve(ctx, req);
                    }));
            sb.service("/thrift-devnull", THttpService.of(devNullServiceHandler));
        }
    };

    @Test
    public void execute() throws Exception {
        final HelloService.Iface client = helloClient(retryOnException, 100);
        when(serviceHandler.hello(anyString())).thenReturn("world");

        assertThat(client.hello("hello")).isEqualTo("world");
        verify(serviceHandler, only()).hello("hello");
    }

    @Test
    public void execute_retry() throws Exception {
        final HelloService.Iface client = helloClient(retryOnException, 100);
        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("world");

        assertThat(client.hello("hello")).isEqualTo("world");
        verify(serviceHandler, times(3)).hello("hello");
    }

    @Test
    public void execute_reachedMaxAttempts() throws Exception {
        final HelloService.Iface client = helloClient(retryAlways, 2);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        final Throwable thrown = catchThrowable(() -> client.hello("hello"));
        assertThat(thrown).isInstanceOf(TApplicationException.class);
        assertThat(((TApplicationException) thrown).getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
        verify(serviceHandler, times(2)).hello("hello");
    }

    @Test
    public void propagateLastResponseWhenNextRetryIsAfterTimeout() throws Exception {
        final RetryStrategyWithContent<RpcResponse> strategy =
                (ctx, response) -> CompletableFuture.completedFuture(Backoff.fixed(10000000));
        final HelloService.Iface client = helloClient(strategy, 100);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        final Throwable thrown = catchThrowable(() -> client.hello("hello"));
        assertThat(thrown).isInstanceOf(TApplicationException.class);
        assertThat(((TApplicationException) thrown).getType()).isEqualTo(TApplicationException.INTERNAL_ERROR);
        verify(serviceHandler, only()).hello("hello");
    }

    private HelloService.Iface helloClient(RetryStrategyWithContent<RpcResponse> strategy,
                                           int maxAttempts) {
        return new ClientBuilder(server.uri(BINARY, "/thrift"))
                .rpcDecorator(new RetryingRpcClientBuilder(strategy)
                                      .maxTotalAttempts(maxAttempts)
                                      .newDecorator())
                .build(HelloService.Iface.class);
    }

    @Test
    public void execute_void() throws Exception {
        final DevNullService.Iface client = new ClientBuilder(server.uri(BINARY, "/thrift-devnull"))
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
    public void shouldGetExceptionWhenFactoryIsClosed() throws Exception {
        final ClientFactory factory = new ClientFactoryBuilder()
                .workerGroup(EventLoopGroups.newEventLoopGroup(2), true).build();

        final RetryStrategyWithContent<RpcResponse> strategy =
                (ctx, response) -> {
                    // Retry after 8000 which is slightly less than responseTimeoutMillis(10000).
                    return CompletableFuture.completedFuture(Backoff.fixed(8000));
                };

        final HelloService.Iface client = new ClientBuilder(server.uri(BINARY, "/thrift"))
                .defaultResponseTimeoutMillis(10000)
                .factory(factory)
                .rpcDecorator(new RetryingRpcClientBuilder(strategy).newDecorator())
                .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        // There's no way to notice that the RetryingClient has scheduled the next retry.
        // The next retry will be after 8 seconds so closing the factory after 3 seconds should work.
        Executors.newSingleThreadScheduledExecutor().schedule(factory::close, 3, TimeUnit.SECONDS);
        assertThatThrownBy(() -> client.hello("hello"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(cause -> assertThat(cause.getMessage()).matches(
                        "(?i).*(factory has been closed|not accepting a task).*"));
    }

    @Test
    public void doNotRetryWhenResponseIsCancelled() throws Exception {
        final HelloService.Iface client = new ClientBuilder(server.uri(BINARY, "/thrift"))
                .rpcDecorator(new RetryingRpcClientBuilder(retryAlways).newDecorator())
                .rpcDecorator((delegate, ctx, req) -> {
                    final RpcResponse res = delegate.execute(ctx, req);
                    res.cancel(true);
                    return res;
                })
                .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString())).thenThrow(new IllegalArgumentException());

        assertThatThrownBy(() -> client.hello("hello")).isInstanceOf(CancellationException.class);
        await().untilAsserted(() -> verify(serviceHandler, only()).hello("hello"));

        // Sleep 1 second more to check if there was another retry.
        TimeUnit.SECONDS.sleep(1);
        verify(serviceHandler, only()).hello("hello");
    }
}
