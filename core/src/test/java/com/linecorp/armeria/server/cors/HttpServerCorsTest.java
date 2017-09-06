/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server.cors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.AsciiString;

public class HttpServerCorsTest {

    private static final ClientFactory clientFactory = ClientFactory.DEFAULT;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/cors", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }

                @Override
                protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }

                @Override
                protected void doHead(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }

                @Override
                protected void doOptions(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.respond(HttpStatus.OK);
                }
            }.decorate(CorsServiceBuilder.forOrigin("http://example.com")
                                         .allowRequestMethods(HttpMethod.POST)
                                         .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                                         .newDecorator()));
        }
    };

    @Test
    public void testCorsPreflight() throws Exception {
        HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.OPTIONS, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("Hello CORS", response.headers().get(AsciiString.of("x-preflight-cors")));
    }

    @Test
    public void testCorsAllowed() throws Exception {
        HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testCorsForbidden() throws Exception {
        HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.org")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")).aggregate().get();

        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
