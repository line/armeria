/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;

class RpcDecoratorResponseLogCompletionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", THttpService.builder()
                                        .addService(new HelloServiceImpl())
                                        .build());
        }
    };

    @Test
    void testSimple_success() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .rpcDecorator((delegate, ctx, req) -> {
                                 return RpcResponse.of(null);
                             })
                             .build(HelloService.Iface.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final String response = client.hello("/");
            assertThat(response).isNull();
            final ClientRequestContext ctx = captor.get();
            // Make sure that the log is completed.
            final RequestLog log = ctx.log().whenComplete().join();
            // The headers are not logged.
            assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.UNKNOWN);
        }
    }

    @Test
    void testSimple_failure() {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .rpcDecorator((delegate, ctx, req) -> {
                                 return RpcResponse.ofFailure(new AnticipatedException());
                             })
                             .build(HelloService.Iface.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> client.hello("/"))
                    .isInstanceOf(AnticipatedException.class);
            final ClientRequestContext ctx = captor.get();
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.responseCause()).isInstanceOf(AnticipatedException.class);
        }
    }

    @Test
    void testRetryingRpcClient_success() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .rpcDecorator((delegate, ctx, req) -> {
                                 return RpcResponse.of(null);
                             })
                             .rpcDecorator(RetryingRpcClient.newDecorator(RetryRuleWithContent.onException()))
                             .build(HelloService.Iface.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final String response = client.hello("hello");
            final ClientRequestContext ctx = captor.get();
            assertThat(response).isNull();
            // Make sure that the log is completed.
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.children().size()).isEqualTo(1);
            assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.UNKNOWN);
        }
    }

    @ValueSource(booleans = {true, false})
    @ParameterizedTest
    void testRetryingClient_responseFailure(boolean throwException) {
        final HelloService.Iface client =
                ThriftClients.builder(server.httpUri())
                         .rpcDecorator((delegate, ctx, req) -> {
                             if (throwException) {
                                 throw new AnticipatedException();
                             } else {
                                 return RpcResponse.ofFailure(new AnticipatedException());
                             }
                         })
                         .rpcDecorator(RetryingRpcClient.builder(RetryRuleWithContent.onException())
                                                  .maxTotalAttempts(2)
                                                  .newDecorator())
                         .build(HelloService.Iface.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> {
                client.hello("hello");
            }).isInstanceOf(AnticipatedException.class);
            final ClientRequestContext ctx = captor.get();
            // Make sure that the log is completed.
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.responseCause()).isInstanceOf(AnticipatedException.class);
            assertThat(log.children().size()).isEqualTo(2);
        }
    }

    private static class HelloServiceImpl implements HelloService.Iface {

        @Override
        public String hello(String name) throws TException {
            return "Hello " + name;
        }
    }
}
