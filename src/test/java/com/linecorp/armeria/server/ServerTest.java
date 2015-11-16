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
package com.linecorp.armeria.server;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Promise;

public class ServerTest extends AbstractServerTest {

    private static final long processDelayMillis = 1000;
    private static final long requestTimeoutMillis = 500;
    private static final long idleTimeoutMillis = 500;

    private static final EventExecutorGroup asyncExecutorGroup = new DefaultEventExecutorGroup(1);

    @Override
    protected void configureServer(ServerBuilder sb) {

        final Service immediateResponseOnIoThread = new ByteBufService(
                (ctx, exec, promise) -> promise.setSuccess(((ReferenceCounted) ctx.params().get(0)).retain()))
                .decorate(LoggingService::new);

        final Service delayedResponseOnIoThread = new ByteBufService((ctx, exec, promise) -> {
            Thread.sleep(processDelayMillis);
            final ByteBuf buf = ((ByteBuf) ctx.params().get(0)).retain();
            promise.setSuccess(buf);
        }).decorate(LoggingService::new);

        final Service lazyResponseNotOnIoThread = new ByteBufService((ctx, exec, promise) -> {
            final ByteBuf buf = ((ByteBuf) ctx.params().get(0)).retain();
            asyncExecutorGroup.schedule(
                    () -> promise.setSuccess(buf),
                    processDelayMillis, TimeUnit.MILLISECONDS);
        }).decorate(LoggingService::new);

        final VirtualHost defaultVirtualHost =
                new VirtualHostBuilder().serviceAt("/", immediateResponseOnIoThread)
                                        .serviceAt("/delayed", delayedResponseOnIoThread)
                                        .serviceAt("/timeout", lazyResponseNotOnIoThread)
                                        .serviceAt("/timeout-not", lazyResponseNotOnIoThread).build();

        sb.defaultVirtualHost(defaultVirtualHost);
        // Disable request timeout for '/timeout-not' only.
        sb.requestTimeout(ctx -> "/timeout-not".equals(ctx.path()) ? 0 : requestTimeoutMillis);
        sb.idleTimeoutMillis(idleTimeoutMillis);
    }

    @Test
    public void testStartStop() throws Exception {
        try {
            assertThat(server().activePorts().size(), is(1));
            server().stop().sync();
            assertThat(server().activePorts().size(), is(0));
        } finally {
            stopServer();
        }
    }

    @Test
    public void testInvocation() throws Exception {
        testInvocation0("/");
    }

    @Test
    public void testDelayedResponseApiInvocationExpectedTimeout() throws Exception {
        testInvocation0("/delayed");
    }

    private static void testInvocation0(String path) throws IOException {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(uri(path));
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(EntityUtils.toString(res.getEntity()), is("Hello, world!"));
            }
        }
    }

    @Test
    public void testRequestTimeoutInvocation() throws Exception {
         try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(uri("/timeout"));
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(HttpStatusClass.valueOf(res.getStatusLine().getStatusCode()), not(
                        is(HttpStatusClass.SUCCESS)));
            }
        }
    }

    @Test
    public void testDynamicRequestTimeoutInvocation() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(uri("/timeout-not"));
            req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(HttpStatusClass.valueOf(res.getStatusLine().getStatusCode()), is(
                        is(HttpStatusClass.SUCCESS)));
            }
        }
    }

    @Test(timeout = idleTimeoutMillis * 5)
    public void testIdleTimeoutByNoContentSent() throws Exception {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) (idleTimeoutMillis * 4));
            socket.connect(server().activePort().get().localAddress());
            long connectedNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }
            long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - connectedNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis, is(greaterThanOrEqualTo(idleTimeoutMillis)));
        }
    }

    @Test(timeout = idleTimeoutMillis * 5)
    public void testIdleTimeoutByContentSent() throws Exception {
        HttpPost req = new HttpPost(uri("/"));
        req.setEntity(new StringEntity("Hello, world!", StandardCharsets.UTF_8));

        try (Socket socket = new Socket()) {
            socket.setSoTimeout((int) (idleTimeoutMillis * 4));
            socket.connect(server().activePort().get().localAddress());
            PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);
            outWriter.print("POST / HTTP/1.1\r\n");
            outWriter.print("Connection: Keep-Alive\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            long lastWriteNanos = System.nanoTime();
            //read until EOF
            while (socket.getInputStream().read() != -1) {
                continue;
            }

            long elapsedTimeMillis = TimeUnit.MILLISECONDS.convert(
                    System.nanoTime() - lastWriteNanos, TimeUnit.NANOSECONDS);
            assertThat(elapsedTimeMillis, is(greaterThanOrEqualTo(idleTimeoutMillis)));
        }
    }

    private static class ByteBufService extends SimpleService {

        ByteBufService(ServiceInvocationHandler handler) {
            super(new ServiceCodec() {
                @Override
                public DecodeResult decodeRequest(
                        Channel ch, SessionProtocol sessionProtocol,
                        String hostname, String path, String mappedPath,
                        ByteBuf in, Object originalRequest, Promise<Object> promise) throws Exception {

                    return new DefaultDecodeResult(
                            new ServiceInvocationContext(
                                    ch, Scheme.of(SerializationFormat.THRIFT_BINARY, sessionProtocol),
                                    hostname, path, mappedPath,
                                    getClass().getName(), /* originalRequest */ null) {

                                @Override
                                public String method() {
                                    return "invoke";
                                }

                                @Override
                                public List<Class<?>> paramTypes() {
                                    return Collections.singletonList(ByteBuf.class);
                                }

                                @Override
                                public Class<?> returnType() {
                                    return ByteBuf.class;
                                }

                                @Override
                                public String invocationId() {
                                    return "?";
                                }

                                @Override
                                public List<Object> params() {
                                    return Collections.singletonList(in);
                                }
                            });
                }

                @Override
                public boolean failureResponseFailsSession(ServiceInvocationContext ctx) {
                    return true;
                }

                @Override
                public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
                    return (ByteBuf) response;
                }

                @Override
                public ByteBuf encodeFailureResponse(
                        ServiceInvocationContext ctx, Throwable cause) throws Exception {
                    return Unpooled.copiedBuffer(cause.toString(), StandardCharsets.UTF_8);
                }
            }, handler);
        }
    }
}
