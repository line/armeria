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

package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.AsyncIface;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class ClientRequestContextPushedOnCallbackTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", THttpService.of((HelloService.AsyncIface) (name, resultHandler)
                    -> resultHandler.onComplete("Hello, " + name + '!')));
            sb.service("/exception", THttpService.of((HelloService.AsyncIface) (name, resultHandler)
                    -> resultHandler.onError(new Exception())));
        }
    };

    @Test
    void pushedContextOnAsyncMethodCallback() throws Exception {
        final AtomicReference<ClientRequestContext> ctxHolder = new AtomicReference<>();
        final AsyncIface client = Clients.newClient(server.httpUri(BINARY) + "/hello", AsyncIface.class);

        final ClientRequestContext ctx;
        final CountDownLatch latch = new CountDownLatch(1);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.hello("foo", new AsyncMethodCallback<String>() {
                @Override
                public void onComplete(String response) {
                    assertThat(response).isEqualTo("Hello, foo!");
                    ctxHolder.set(RequestContext.currentOrNull());
                    latch.countDown();
                }

                @Override
                public void onError(Exception exception) {}
            });
            ctx = captor.get();
        }

        latch.await();
        assertThat(ctx).isSameAs(ctxHolder.get());
    }

    @Test
    void pushedContextOnAsyncMethodCallback_onError() throws Exception {
        final AsyncIface client = Clients.newClient(server.httpUri(BINARY) + "/exception", AsyncIface.class);
        checkContextOnAsyncMethodCallbackOnError(client);
    }

    private static void checkContextOnAsyncMethodCallbackOnError(AsyncIface client) throws Exception {
        final AtomicReference<ClientRequestContext> ctxHolder = new AtomicReference<>();
        final ClientRequestContext ctx;
        final CountDownLatch latch = new CountDownLatch(1);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.hello("foo", new AsyncMethodCallback<String>() {
                @Override
                public void onComplete(String response) {}

                @Override
                public void onError(Exception exception) {
                    ctxHolder.set(RequestContext.currentOrNull());
                    latch.countDown();
                }
            });
            ctx = captor.get();
        }

        latch.await();
        assertThat(ctx).isSameAs(ctxHolder.get());
    }

    @Test
    void pushedContextOnAsyncMethodCallback_exceptionInDecorator() throws Exception {
        final AsyncIface client = Clients.builder(server.httpUri(BINARY) + "/exception")
                                         .rpcDecorator((delegate, ctx, req) -> {
                                             throw new AnticipatedException();
                                         }).build(AsyncIface.class);

        checkContextOnAsyncMethodCallbackOnError(client);
    }
}
