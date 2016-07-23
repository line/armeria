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

package com.linecorp.armeria.client.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.RemoteInvokerFactory;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.HttpService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class SimpleHttpClientIntegrationTest {

    private static final Server server;

    private static int httpPort;
    private static RemoteInvokerFactory remoteInvokerFactory;

    static {
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.port(0, SessionProtocol.HTTP);
            sb.cors(CorsConfigBuilder.forOrigin("http://example.com")
                    .allowedRequestMethods(HttpMethod.POST)
                    .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                    .build());
            sb.serviceAt("/httptestbody", new HttpService(
                    (ctx, executor, promise) -> {
                        FullHttpRequest request = ctx.originalRequest();
                        if (request.headers().get(HttpHeaderNames.CONTENT_TYPE) != null) {
                            throw new IllegalArgumentException(
                                    "Serialization format is none, so content type should not be set!");
                        }
                        if (!"utf-8".equals(request.headers().get(HttpHeaderNames.ACCEPT))) {
                            throw new IllegalArgumentException(
                                    "Serialization format is none, so accept should be set to netty default!");
                        }
                        ByteBuf content = ctx.alloc().ioBuffer();
                        byte[] body = ByteBufUtil.getBytes(request.content());
                        content.writeBytes(String.format(
                                "METHOD: %s|ACCEPT: %s|BODY: %s",
                                request.method().name(),
                                request.headers().get(HttpHeaderNames.ACCEPT),
                                new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));

                        DefaultFullHttpResponse response =
                                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                                            content, false);
                        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "alwayscache");
                        promise.setSuccess(response);
                    }));
            sb.serviceAt("/not200", new HttpService((ctx, executor, promise) -> {
                DefaultFullHttpResponse response =
                        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                promise.setSuccess(response);
            }));
            sb.serviceAt("/cors", new HttpService((ctx, executor, promise) -> {
                DefaultFullHttpResponse response =
                        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                promise.setSuccess(response);
            }));
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().sync();
        httpPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress()
                .getPort();
        remoteInvokerFactory = RemoteInvokerFactory.DEFAULT;
    }

    @AfterClass
    public static void destroy() throws Exception {
        remoteInvokerFactory.close();
        server.stop();
    }

    @Test
    public void testRequestNoBody() throws Exception {
        SimpleHttpClient client = Clients.newClient(remoteInvokerFactory, "none+http://127.0.0.1:" + httpPort,
                                                    SimpleHttpClient.class);
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/httptestbody")
                .header(HttpHeaderNames.ACCEPT, "utf-8")
                .build();
        SimpleHttpResponse response = client.execute(request).get();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: GET|ACCEPT: utf-8|BODY: ",
                     new String(response.content(), StandardCharsets.UTF_8));
    }

    @Test
    public void testRequestWithBody() throws Exception {
        SimpleHttpClient client = Clients.newClient(remoteInvokerFactory, "none+http://127.0.0.1:" + httpPort,
                                                    SimpleHttpClient.class);
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forPost("/httptestbody")
                .header(HttpHeaderNames.ACCEPT, "utf-8")
                .content("requestbody日本語", StandardCharsets.UTF_8)
                .build();
        SimpleHttpResponse response = client.execute(request).get();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: POST|ACCEPT: utf-8|BODY: requestbody日本語",
                     new String(response.content(), StandardCharsets.UTF_8));
    }

    @Test
    public void testNot200() throws Exception {
        SimpleHttpClient client = Clients.newClient(remoteInvokerFactory, "none+http://127.0.0.1:" + httpPort,
                                                    SimpleHttpClient.class);
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/not200").build();
        SimpleHttpResponse response = client.execute(request).get();
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
    }

    @Test
    public void testCORSPreflight() throws Exception {
        SimpleHttpClient client = Clients.newClient(remoteInvokerFactory, "none+http://127.0.0.1:" + httpPort,
                SimpleHttpClient.class);
        // pre-flight request
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forOptions("/cors")
                .header(HttpHeaderNames.ACCEPT, "utf-8")
                .header(HttpHeaderNames.ORIGIN, "http://example.com")
                .header(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.asciiName())
                .build();
        SimpleHttpResponse response = client.execute(request).get();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("Hello CORS", response.headers().get("x-preflight-cors"));
    }

    @Test
    public void testCORSAllowed() throws Exception {
        SimpleHttpClient client = Clients.newClient(remoteInvokerFactory, "none+http://127.0.0.1:" + httpPort,
                SimpleHttpClient.class);
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forPost("/cors")
                .header(HttpHeaderNames.ACCEPT, "utf-8")
                .header(HttpHeaderNames.ORIGIN, "http://example.com")
                .header(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.asciiName())
                .build();
        SimpleHttpResponse response = client.execute(request).get();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testCORSForbidden() throws Exception {
        SimpleHttpClient client = Clients.newClient(remoteInvokerFactory, "none+http://127.0.0.1:" + httpPort,
                SimpleHttpClient.class);
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forOptions("/cors")
                .header(HttpHeaderNames.ACCEPT, "utf-8")
                .header(HttpHeaderNames.ORIGIN, "http://example.org")
                .header(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.asciiName())
                .build();
        SimpleHttpResponse response = client.execute(request).get();
        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
