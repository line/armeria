/*
 * Copyright 2024 LINE Corporation
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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import testing.thrift.main.HelloService;

class RpcPreprocessorTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void overwriteByCustomPreprocessor() throws Exception {
        final RpcPreprocessor rpcPreprocessor =
                RpcPreprocessor.of(SessionProtocol.HTTP, Endpoint.of("127.0.0.1"),
                                   eventLoop.get());
        final HelloService.Iface iface =
                ThriftClients.builder("http://127.0.0.2")
                             .rpcPreprocessor(rpcPreprocessor)
                             .rpcDecorator((delegate, ctx, req) -> RpcResponse.of("world"))
                             .build(HelloService.Iface.class);
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(iface.hello("world")).isEqualTo("world");
            ctx = captor.get();
        }
        assertThat(ctx.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(ctx.authority()).isEqualTo("127.0.0.1");
        assertThat(ctx.eventLoop().withoutContext()).isSameAs(eventLoop.get());
    }

    @Test
    void preprocessorOrder() throws Exception {
        final List<String> list = new ArrayList<>();
        final RpcPreprocessor p1 = RunnablePreprocessor.of(() -> list.add("1"));
        final RpcPreprocessor p2 = RunnablePreprocessor.of(() -> list.add("2"));
        final RpcPreprocessor p3 = RunnablePreprocessor.of(() -> list.add("3"));

        final HelloService.Iface iface =
                ThriftClients.builder("http://127.0.0.2")
                             .rpcPreprocessor(p1)
                             .rpcPreprocessor(p2)
                             .rpcPreprocessor(p3)
                             .rpcDecorator((delegate, ctx, req) -> RpcResponse.of("world"))
                             .build(HelloService.Iface.class);
        assertThat(iface.hello("world")).isEqualTo("world");
        assertThat(list).containsExactly("3", "2", "1");
    }

    private static final class RunnablePreprocessor implements RpcPreprocessor {

        private static RpcPreprocessor of(Runnable runnable) {
            return new RunnablePreprocessor(runnable);
        }

        private final Runnable runnable;

        private RunnablePreprocessor(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public RpcResponse execute(PreClient<RpcRequest, RpcResponse> delegate,
                                   PreClientRequestContext ctx, RpcRequest req) throws Exception {
            runnable.run();
            return delegate.execute(ctx, req);
        }
    }
}
