/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.it.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.HttpHeaderInjectingRpcService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftCallService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.AsciiString;

public class ThriftHttpHeaderInjectingServiceTest {

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) {
            final HelloService.Iface helloSync = name -> "Hello, " + name + '!';
            sb.service("/sync", ThriftCallService.of(helloSync)
                                                 .decorate(HeaderInjector::new)
                                                 .decorate(THttpService.newDecorator()));

            final HelloService.AsyncIface helloAsync = (name, resultHandler)
                    -> resultHandler.onComplete("Hello, " + name + '!');
            sb.service("/async", ThriftCallService.of(helloAsync)
                                                  .decorate(HeaderInjector::new)
                                                  .decorate(THttpService.newDecorator()));
        }
    };

    @Test
    public void httpHeaderInjection() throws Exception {
        final AtomicInteger counter = new AtomicInteger();

        final HelloService.Iface client = new ClientBuilder(server.uri(BINARY, "/sync"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           delegate -> new HeaderFilter(delegate, () -> "hello#" + counter.incrementAndGet()))
                .build(HelloService.Iface.class);

        assertThat(client.hello("foo")).isEqualTo("Hello, foo!");
        assertThat(client.hello("foo")).isEqualTo("Hello, foo!");

        counter.set(0);
        final HelloService.AsyncIface asyncClient = new ClientBuilder(server.uri(BINARY, "/async"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           delegate -> new HeaderFilter(delegate, () -> "hello#" + counter.incrementAndGet()))
                .build(HelloService.AsyncIface.class);

        final CompletableFuture<String> future = new CompletableFuture<>();
        asyncClient.hello("foo", new Callback(future));
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(future.join()).isEqualTo("Hello, foo!"));
    }

    private static final class HeaderInjector extends HttpHeaderInjectingRpcService {

        private final AtomicInteger counter = new AtomicInteger();

        HeaderInjector(Service<RpcRequest, RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        protected HttpHeaders httpHeaders(ServiceRequestContext ctx, RpcRequest req, RpcResponse res) {
            return HttpHeaders.of(AsciiString.of("x-req-method"),
                                  req.method() + '#' + counter.incrementAndGet());
        }
    }

    private static final class HeaderFilter extends SimpleDecoratingClient<HttpRequest, HttpResponse> {
        private final Supplier<String> expectedHeaderValue;

        HeaderFilter(Client<HttpRequest, HttpResponse> delegate, Supplier<String> expectedHeaderValue) {
            super(delegate);
            this.expectedHeaderValue = expectedHeaderValue;
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            final HttpResponse res = delegate().execute(ctx, req);
            return new FilteredHttpResponse(res) {
                @Override
                protected HttpObject filter(HttpObject obj) {
                    if (obj instanceof HttpHeaders) {
                        assertHeaders((HttpHeaders) obj);
                    }
                    return obj;
                }
            };
        }

        private void assertHeaders(HttpHeaders headers) {
            assertThat(headers.get(AsciiString.of("x-req-method"))).isEqualTo(expectedHeaderValue.get());
        }
    }

    private static final class Callback implements AsyncMethodCallback<String> {
        private CompletableFuture<String> future;

        Callback(CompletableFuture<String> future) {
            this.future = future;
        }

        @Override
        public void onComplete(String response) {
            future.complete(response);
        }

        @Override
        public void onError(Exception exception) {
            fail();
        }
    }
}
