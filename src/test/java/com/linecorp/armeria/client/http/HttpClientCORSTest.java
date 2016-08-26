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

import static io.netty.handler.codec.http.HttpMethod.POST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.util.AsciiString;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

public class HttpClientCORSTest {

    private static final Server server;

    private static int httpPort;
    private static ClientFactory clientFactory;

    static {
        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.port(0, SessionProtocol.HTTP);
            sb.cors(CorsConfigBuilder.forOrigin("http://example.com")
                    .allowedRequestMethods(POST)
                    .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                    .build());

            sb.serviceAt("/cors", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                                     HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }
                @Override
                protected void doPost(ServiceRequestContext ctx, HttpRequest req,
                                      HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }
                @Override
                protected void doHead(ServiceRequestContext ctx, HttpRequest req,
                                      HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }
                @Override
                protected void doOptions(ServiceRequestContext ctx, HttpRequest req,
                                         HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
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

    @Test
    public void testCORSPreflight() throws Exception {
        HttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
                HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.OPTIONS, "/")
                        .set(HttpHeaderNames.ACCEPT, "utf-8")
                        .set(HttpHeaderNames.ORIGIN, "http://example.com")
                        .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("Hello CORS", response.headers().get(AsciiString.of("x-preflight-cors")));
    }

    @Test
    public void testCORSAllowed() throws Exception {
        HttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
                HttpClient.class);
        HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, "/cors")
                .set(HttpHeaderNames.ACCEPT, "utf-8")
                .set(HttpHeaderNames.ORIGIN, "http://example.com")
                .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        AggregatedHttpMessage response = client.execute(headers).aggregate().get();
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testCORSForbidden() throws Exception {
        HttpClient client = Clients.newClient(clientFactory, "none+http://127.0.0.1:" + httpPort,
                HttpClient.class);
        HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, "/cors")
                .set(HttpHeaderNames.ACCEPT, "utf-8")
                .set(HttpHeaderNames.ORIGIN, "http://example.org")
                .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        AggregatedHttpMessage response = client.execute(headers).aggregate().get();
        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

}
