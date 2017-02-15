/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.it.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryRequestStrategy;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.test.AbstractServiceServer;

public class RetryingRpcClientTest {
    private static final RetryRequestStrategy<RpcRequest, RpcResponse> ALWAYS = (unused1, unused2) -> true;
    private static final RetryRequestStrategy<RpcRequest, RpcResponse> ALWAYS_HANDLES_EXCEPTION =
            (request, response) -> false;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    HelloService.Iface serviceHandler;

    @Test
    public void execute() throws Exception {
        try (ServiceServer server = new ServiceServer(serviceHandler).start()) {
            HelloService.Iface client = new ClientBuilder(
                    "tbinary+http://127.0.0.1:" + server.port() + "/thrift")
                    .decorator(RpcRequest.class, RpcResponse.class,
                               RetryingRpcClient.newDecorator())
                    .build(HelloService.Iface.class);
            when(serviceHandler.hello(anyString()))
                    .thenReturn("world");
            assertThat(client.hello("hello")).isEqualTo("world");
            verify(serviceHandler, only()).hello("hello");
        }
    }

    @Test
    public void execute_retry() throws Exception {
        try (ServiceServer server = new ServiceServer(serviceHandler).start()) {
            HelloService.Iface client = new ClientBuilder(
                    "tbinary+http://127.0.0.1:" + server.port() + "/thrift")
                    .decorator(RpcRequest.class, RpcResponse.class,
                               RetryingRpcClient.newDecorator(ALWAYS_HANDLES_EXCEPTION))
                    .build(HelloService.Iface.class);
            when(serviceHandler.hello(anyString()))
                    .thenThrow(new IllegalArgumentException())
                    .thenThrow(new IllegalArgumentException())
                    .thenReturn("world");
            assertThat(client.hello("hello")).isEqualTo("world");
            verify(serviceHandler, times(3)).hello("hello");
        }
    }

    @Test
    public void execute_reachedMaxAttempts() throws Exception {
        try (ServiceServer server = new ServiceServer(serviceHandler).start()) {
            HelloService.Iface client = new ClientBuilder(
                    "tbinary+http://127.0.0.1:" + server.port() + "/thrift")
                    .decorator(RpcRequest.class, RpcResponse.class,
                               RetryingRpcClient.newDecorator(ALWAYS,
                                                              () -> Backoff.withoutDelay().withMaxAttempts(1)))
                    .build(HelloService.Iface.class);
            when(serviceHandler.hello(anyString()))
                    .thenThrow(new IllegalArgumentException());
            assertThatThrownBy(() -> client.hello("hello")).isInstanceOf(Exception.class);
            verify(serviceHandler, times(2)).hello("hello");
        }
    }

    private static class ServiceServer extends AbstractServiceServer {
        private final THttpService handler;

        ServiceServer(HelloService.Iface handler) {
            this.handler = THttpService.of(handler);
        }

        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            sb.serviceAt("/thrift", handler);
        }
    }
}
