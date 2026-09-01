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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.SimpleDecoratingRpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

import testing.thrift.main.HelloService;

class RpcClientDecorationTest {

    @Test
    void lambdaRpcDecorator() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder("http://127.0.0.1/")
                             .rpcDecorator(delegate -> (ctx, req) -> RpcResponse.of("lambda"))
                             .build(HelloService.Iface.class);

        final String response = client.hello("world");
        assertThat(response).isEqualTo("lambda");
    }

    @Test
    void lambdaRpcDecoratorThatDelegates() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder("http://127.0.0.1/")
                             // inner: returns a response directly
                             .rpcDecorator(delegate -> (ctx, req) -> RpcResponse.of("hello"))
                             // outer: lambda that delegates to inner
                             .rpcDecorator(delegate -> (ctx, req) -> delegate.execute(ctx, req))
                             .build(HelloService.Iface.class);

        final String response = client.hello("world");
        assertThat(response).isEqualTo("hello");
    }

    @Test
    void mixedLambdaAndSimpleDecoratingRpcClient() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder("http://127.0.0.1/")
                             // inner: lambda decorator
                             .rpcDecorator(delegate -> (ctx, req) -> RpcResponse.of("from-lambda"))
                             // outer: SimpleDecoratingRpcClient
                             .rpcDecorator(delegate -> new SimpleDecoratingRpcClient(delegate) {
                                 @Override
                                 public RpcResponse execute(ClientRequestContext ctx, RpcRequest req)
                                         throws Exception {
                                     return unwrap().execute(ctx, req);
                                 }
                             })
                             .build(HelloService.Iface.class);

        final String response = client.hello("world");
        assertThat(response).isEqualTo("from-lambda");
    }

    @Test
    void multipleLambdaRpcDecorators() throws TException {
        final HelloService.Iface client =
                ThriftClients.builder("http://127.0.0.1/")
                             .rpcDecorator(delegate -> (ctx, req) -> RpcResponse.of("first"))
                             .rpcDecorator(delegate -> (ctx, req) -> delegate.execute(ctx, req))
                             .rpcDecorator(delegate -> (ctx, req) -> delegate.execute(ctx, req))
                             .build(HelloService.Iface.class);

        final String response = client.hello("world");
        assertThat(response).isEqualTo("first");
    }

    @Test
    void rpcDecoratorWrappingWrongDelegate() {
        final RpcClient other = (ctx, req) -> RpcResponse.of("other");
        assertThatThrownBy(() -> {
            ThriftClients.builder("http://127.0.0.1/")
                         .rpcDecorator(delegate -> new SimpleDecoratingRpcClient(other) {
                             @Override
                             public RpcResponse execute(ClientRequestContext ctx, RpcRequest req)
                                     throws Exception {
                                 return unwrap().execute(ctx, req);
                             }
                         })
                         .build(HelloService.Iface.class);
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Failed to find TailRpcClient");
    }
}
