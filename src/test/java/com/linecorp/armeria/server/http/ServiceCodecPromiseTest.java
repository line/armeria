/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.http;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.DecoratingServiceCodec;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceInvocationHandler;
import com.linecorp.armeria.server.VirtualHostBuilder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

@RunWith(Enclosed.class)
public class ServiceCodecPromiseTest {

    protected static void setupServer(
            ServerBuilder sb, GenericFutureListener<? extends Future<? super Object>> listener,
            ServiceInvocationHandler handler) {
        Service service = Service.of(new DecoratingServiceCodec(new HttpServiceCodec()) {
            @Override
            public DecodeResult decodeRequest(ServiceConfig cfg, Channel ch, SessionProtocol sessionProtocol,
                                              String hostname, String path, String mappedPath, ByteBuf in,
                                              Object originalRequest, Promise<Object> promise) throws Exception {
                promise.addListener(listener);
                return delegate().decodeRequest(cfg, ch, sessionProtocol, hostname, path, mappedPath, in,
                                                originalRequest, promise);
            }

            @Override
            public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
                return Unpooled.EMPTY_BUFFER;
            }
        }, handler);

        sb.serviceAt("/", service);
        sb.serviceAt("/:variable/*", service);
    }

    abstract static class ExecutionCheckingTest extends AbstractServerTest {
        private boolean executed;

        protected GenericFutureListener<? extends Future<? super Object>> defaultListener =
                (Future<? super Object> future) -> executed = true;

        @Test
        public void test() throws Exception {
            try (CloseableHttpClient hc = HttpClients.createMinimal()) {
                final HttpPost req = new HttpPost(uri("/"));
                req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

                hc.execute(req);

                assertTrue(executed);
            }
        }
    }

    public static class NormalResponseTest extends ExecutionCheckingTest {
        @Override
        protected void configureServer(ServerBuilder sb) {
            setupServer(sb, defaultListener,
                        (ctx, blockingTaskExecutor, promise) -> promise.trySuccess("Hello World"));
        }
    }

    public static class HttpResponseTest extends ExecutionCheckingTest {
        @Override
        protected void configureServer(ServerBuilder sb) {
            setupServer(sb, defaultListener,
                        (ctx, blockingTaskExecutor, promise) -> promise.trySuccess(new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.OK
                        )));
        }
    }

    public static class ExceptionResponseTest extends ExecutionCheckingTest {
        @Override
        protected void configureServer(ServerBuilder sb) {
            setupServer(sb, defaultListener,
                        (ctx, blockingTaskExecutor, promise) -> promise.tryFailure(new RuntimeException()));
        }
    }

    abstract static class RoutingVariableTest extends AbstractServerTest {
        private boolean executed;

        protected GenericFutureListener<? extends Future<? super Object>> defaultListener =
                (Future<? super Object> future) -> executed = true;

        @Test
        public void test() throws Exception {
            try (CloseableHttpClient hc = HttpClients.createMinimal()) {
                final HttpPost req = new HttpPost(uri("/hello/world"));
                hc.execute(req);
                assertTrue(executed);
            }
        }
    }

    public static class RoutingVariableContentTest extends RoutingVariableTest {
        @Override
        protected void configureServer(ServerBuilder sb) {
            setupServer(sb, defaultListener,
                    (ctx, blockingTaskExecutor, promise) -> {
                        String variable = ((HttpServiceInvocationContext)ctx).getMappedVariables("variable");
                        if (!variable.equals("hello")) {
                            promise.tryFailure(new RuntimeException());
                            return;
                        }
                        List<String> globs = ((HttpServiceInvocationContext)ctx).getGlobStrings();
                        if (!globs.equals("world")) {
                            promise.tryFailure(new RuntimeException());
                            return;
                        }
                        promise.trySuccess("RoutingVariableContentTest");
                    });
        }
    }
}
