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

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.common.net.MediaType;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

import io.netty.handler.codec.http.HttpResponseStatus;

public class HttpClientIntegrationTest {

    private static final Server server;

    private static int httpPort;
    private static ClientFactory clientFactory;

    static {
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.port(0, SessionProtocol.HTTP);

            sb.serviceAt("/httptestbody", new AbstractHttpService() {

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                                     HttpResponseWriter res) {
                    doGetOrPost(req, res);
                }

                @Override
                protected void doPost(ServiceRequestContext ctx, HttpRequest req,
                                      HttpResponseWriter res) {
                    doGetOrPost(req, res);
                }

                private void doGetOrPost(HttpRequest req, HttpResponseWriter res) {
                    final CharSequence contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
                    if (contentType != null) {
                        throw new IllegalArgumentException(
                                "Serialization format is none, so content type should not be set: " +
                                contentType);
                    }

                    final String accept = req.headers().get(HttpHeaderNames.ACCEPT);
                    if (!"utf-8".equals(accept)) {
                        throw new IllegalArgumentException(
                                "Serialization format is none, so accept should not be overridden: " +
                                accept);
                    }

                    req.aggregate().handle(voidFunction((aReq, cause) -> {
                        if (cause != null) {
                            res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                        MediaType.PLAIN_TEXT_UTF_8, Throwables.getStackTraceAsString(cause));
                            return;
                        }

                        res.write(HttpHeaders.of(HttpStatus.OK)
                                             .set(HttpHeaderNames.CACHE_CONTROL, "alwayscache"));
                        res.write(HttpData.ofUtf8(String.format(
                                Locale.ENGLISH,
                                "METHOD: %s|ACCEPT: %s|BODY: %s",
                                req.method().name(), accept,
                                aReq.content().toString(StandardCharsets.UTF_8))));
                        res.close();
                    })).exceptionally(CompletionActions::log);
                }
            });

            sb.serviceAt("/not200", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                                     HttpResponseWriter res) {
                    res.respond(HttpStatus.NOT_FOUND);
                }
            });
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();
        httpPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress()
                .getPort();
        clientFactory = ClientFactory.DEFAULT;
    }

    @AfterClass
    public static void destroy() throws Exception {
        CompletableFuture.runAsync(() -> {
            clientFactory.close();
            server.stop();
        });
    }

    /**
     * When the content of a request is empty, the encoded request should never have 'content-length' or
     * 'transfer-encoding' header.
     */
    @Test
    public void testRequestNoBodyWithoutExtraHeaders() throws Exception {
        ServerSocket ss = null;
        Socket s = null;
        try {
            ss = new ServerSocket(0);
            final int httpPort = ss.getLocalPort();
            final HttpClient client = Clients.newClient(
                    clientFactory, "none+h1c://127.0.0.1:" + httpPort, HttpClient.class);

            client.get("/foo"); // Not interested in the result but only in what the client sends.

            final String expected =
                    "GET /foo HTTP/1.1\r\nuser-agent: Armeria\r\nhost: 127.0.0.1:" + httpPort + "\r\n\r\n";

            ss.setSoTimeout(10000);
            s = ss.accept();
            final byte[] buf = new byte[expected.length()];
            final InputStream in = s.getInputStream();

            // Read the encoded request.
            s.setSoTimeout(10000);
            ByteStreams.readFully(in, buf);

            // Ensure that the encoded request matches.
            assertThat(new String(buf, StandardCharsets.US_ASCII)).isEqualTo(expected);

            // Should not send anything more.
            s.setSoTimeout(1000);
            assertThatThrownBy(in::read).isInstanceOf(SocketTimeoutException.class);
        } finally {
            Closeables.close(s, true);
            Closeables.close(ss, true);
        }
    }

    @Test
    public void testRequestNoBody() throws Exception {
        HttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
                                              HttpClient.class);

        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.GET, "/httptestbody")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")).aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: GET|ACCEPT: utf-8|BODY: ",
                     response.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testRequestNoBodyWithSimpleClient() throws Exception {
        SimpleHttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
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
        HttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
                                              HttpClient.class);

        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/httptestbody")
                           .set(HttpHeaderNames.ACCEPT, "utf-8"),
                "requestbody日本語").aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: POST|ACCEPT: utf-8|BODY: requestbody日本語",
                     response.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testRequestWithBodyWithSimpleClient() throws Exception {
        SimpleHttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
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
        HttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
                                              HttpClient.class);

        AggregatedHttpMessage response = client.get("/not200").aggregate().get();

        assertEquals(HttpStatus.NOT_FOUND, response.headers().status());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testNot200WithSimpleClient() throws Exception {
        SimpleHttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
                                                    SimpleHttpClient.class);
        SimpleHttpRequest request = SimpleHttpRequestBuilder.forGet("/not200").build();
        SimpleHttpResponse response = client.execute(request).get();
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
    }
}
