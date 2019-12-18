/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.DecoratingRpcServiceFunction;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

class ThriftServiceContextAwareTest {

    @Test
    void rpcResponseIsContextAware() throws TException {
        final HelloService.AsyncIface helloService = (name, resultHandler) -> {
            // Use the blocking executor which is not context aware.
            CommonPools.blockingTaskExecutor().schedule(() -> resultHandler.onComplete("hello, " + name),
                                                        100, TimeUnit.MILLISECONDS);
        };
        final AtomicReference<RequestContext> ctxHolder = new AtomicReference<>();
        final AtomicReference<RequestContext> rpcResponseContextAwareCtxHolder = new AtomicReference<>();
        final DecoratingRpcServiceFunction decorator = (delegate, ctx, req) -> {
            ctxHolder.set(ctx);
            final RpcResponse rpcResponse = delegate.serve(ctx, req);
            rpcResponse.handle((unused1, unused2) -> {
                rpcResponseContextAwareCtxHolder.set(RequestContext.currentOrNull());
                return null;
            });
            return rpcResponse;
        };

        final Server server =
                Server.builder().service("/hello", ThriftCallService.of(helloService)
                                                                    .decorate(decorator)
                                                                    .decorate(THttpService.newDecorator()))
                      .build();
        server.start().join();
        final String uri = BINARY.uriText() + "+http://127.0.0.1:" + server.activeLocalPort() + "/hello";
        final HelloService.Iface client = new ClientBuilder(uri).build(HelloService.Iface.class);
        final String res = client.hello("foo");
        assertThat(ctxHolder.get()).isEqualTo(rpcResponseContextAwareCtxHolder.get());
        server.stop().join();
    }
}
