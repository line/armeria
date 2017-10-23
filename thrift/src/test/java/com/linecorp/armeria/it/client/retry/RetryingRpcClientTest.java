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

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryStrategy;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.client.retry.RetryingRpcClientBuilder;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.DevNullService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.server.ServerRule;

public class RetryingRpcClientTest {
    private static final RetryStrategy<RpcRequest, RpcResponse> ALWAYS =
            (request, response) -> {
                final CompletableFuture<Optional<Backoff>> future = new CompletableFuture<>();
                response.handle(voidFunction((unused1, unused2) -> {
                    future.complete(Optional.of(Backoff.withoutDelay()));
                }));
                return future;
            };

    private static final RetryStrategy<RpcRequest, RpcResponse> ONLY_HANDLES_EXCEPTION =
        (request, response) -> {
            final CompletableFuture<Optional<Backoff>> future = new CompletableFuture<>();
            response.handle(voidFunction((unused1, cause) -> {
                if (cause != null) {
                    future.complete(Optional.of(Backoff.withoutDelay()));
                } else {
                    future.complete(Optional.empty());
                }
            }));
            return future;
        };

    @Mock
    private HelloService.Iface serviceHandler = mock(HelloService.Iface.class);

    @Mock
    private DevNullService.Iface devNullServiceHandler = mock(DevNullService.Iface.class);

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/thrift", THttpService.of(serviceHandler));
            sb.service("/thrift-devnull", THttpService.of(devNullServiceHandler));
        }
    };

    @Test
    public void execute() throws Exception {
        HelloService.Iface client = new ClientBuilder(server.uri(BINARY, "/thrift"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           RetryingRpcClient.newDecorator(ONLY_HANDLES_EXCEPTION))
                .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString()))
                .thenReturn("world");
        assertThat(client.hello("hello")).isEqualTo("world");
        verify(serviceHandler, only()).hello("hello");
    }

    @Test
    public void execute_retry() throws Exception {
        HelloService.Iface client = new ClientBuilder(server.uri(BINARY, "/thrift"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           RetryingRpcClient.newDecorator(ONLY_HANDLES_EXCEPTION))
                .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException())
                .thenThrow(new IllegalArgumentException())
                .thenReturn("world");
        assertThat(client.hello("hello")).isEqualTo("world");
        verify(serviceHandler, times(3)).hello("hello");
    }

    @Test
    public void execute_reachedMaxAttempts() throws Exception {
        HelloService.Iface client = new ClientBuilder(server.uri(BINARY, "/thrift"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           new RetryingRpcClientBuilder(ALWAYS).defaultMaxAttempts(1).newDecorator())
                .build(HelloService.Iface.class);
        when(serviceHandler.hello(anyString()))
                .thenThrow(new IllegalArgumentException());
        assertThatThrownBy(() -> client.hello("hello")).isInstanceOf(Exception.class);
        verify(serviceHandler, times(1)).hello("hello");
    }

    @Test
    public void execute_void() throws Exception {
        DevNullService.Iface client = new ClientBuilder(server.uri(BINARY, "/thrift-devnull"))
                .decorator(RpcRequest.class, RpcResponse.class,
                           RetryingRpcClient.newDecorator(ONLY_HANDLES_EXCEPTION, 10)
                )
                .build(DevNullService.Iface.class);

        doThrow(new IllegalArgumentException())
                .doThrow(new IllegalArgumentException())
                .doNothing()
                .when(devNullServiceHandler).consume(anyString());
        client.consume("hello");
        verify(devNullServiceHandler, times(3)).consume("hello");
    }
}
