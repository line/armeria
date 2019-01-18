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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.server.ServerRule;

public class HttpServerCorsTest {

    private static final ClientFactory clientFactory = ClientFactory.DEFAULT;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService myService = new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doOptions(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            };
            sb.service("/cors", myService.decorate(
                    CorsServiceBuilder.forOrigin("http://example.com")
                                      .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                                      .allowRequestHeaders("allow_request_header")
                                      .exposeHeaders("expose_header_1", "expose_header_2")
                                      .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                                      .andForOrigins("http://example2.com")
                                      .allowRequestMethods(HttpMethod.GET)
                                      .allowRequestHeaders(HttpHeaderNames.of("allow_request_header2"))
                                      .exposeHeaders(HttpHeaderNames.of("expose_header_3"),
                                                     HttpHeaderNames.of("expose_header_4"))
                                      .maxAge(3600)
                                      .and()
                                      .newDecorator()));
            // Support short circuit.
            sb.service("/cors2", myService.decorate(
                    CorsServiceBuilder.forOrigin("http://example.com")
                                      .shortCircuit()
                                      .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                                      .allowRequestHeaders(HttpHeaderNames.of("allow_request_header"))
                                      .exposeHeaders(HttpHeaderNames.of("expose_header_1"),
                                                     HttpHeaderNames.of("expose_header_2"))
                                      .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                                      .maxAge(3600)
                                      .andForOrigins("http://example2.com")
                                      .allowNullOrigin()
                                      .allowRequestMethods(HttpMethod.GET)
                                      .allowRequestHeaders(HttpHeaderNames.of("allow_request_header2"))
                                      .exposeHeaders(HttpHeaderNames.of("expose_header_3"),
                                                     HttpHeaderNames.of("expose_header_4"))
                                      .maxAge(1800)
                                      .and()
                                      .newDecorator()));
            sb.service("/cors3", myService.decorate(
                    CorsServiceBuilder.forAnyOrigin()
                                      .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                                      .allowRequestHeaders("allow_request_header")
                                      .exposeHeaders("expose_header_1", "expose_header_2")
                                      .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                                      .maxAge(3600)
                                      .newDecorator()));
        }
    };

    // Makes sure if it throws an Exception when an improper setting is set.
    @Test
    public void testCorsBuilderException() {
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().maxAge(-1)).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().allowNullOrigin()).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().shortCircuit()).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().allowRequestHeaders("", null, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().allowRequestMethods(HttpMethod.GET, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> CorsServiceBuilder.forAnyOrigin().preflightResponseHeader("header", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().preflightResponseHeader("header", Arrays
                .asList("11", null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().preflightResponseHeader("123")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().preflightResponseHeader("123",
                                                                                           ImmutableList.of()));
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().exposeHeaders()).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().allowRequestMethods()).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> CorsServiceBuilder.forAnyOrigin().allowRequestHeaders()).isInstanceOf(
                IllegalArgumentException.class);
    }

    // Makes sure if null origin supported CorsService works properly and it finds the CORS policy
    // which supports null origin.
    @Test
    public void testCorsNullOrigin() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.OPTIONS, "/cors2").set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "null")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().join();

        assertEquals("null", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("GET", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("allow_request_header2",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
    }

    // Makes sure if an any origin supported CorsService works properly and it allows null origin too.
    @Test
    public void testCorsAnyOrigin() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors3").set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().join();
        final AggregatedHttpMessage response2 = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors3").set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "null")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().join();
        final AggregatedHttpMessage response3 = client.execute(
                HttpHeaders.of(HttpMethod.OPTIONS, "/cors3")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().join();
        assertEquals(HttpStatus.OK, response.status());
        assertEquals(HttpStatus.OK, response2.status());
        assertEquals(HttpStatus.OK, response3.status());
        assertEquals("*", response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("GET,POST", response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("allow_request_header",
                     response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("Hello CORS", response3.headers().get(HttpHeaderNames.of("x-preflight-cors")));
    }

    // Makes sure if shortCircuit works properly.
    @Test
    public void testCorsShortCircuit() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors2")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();
        final AggregatedHttpMessage response2 = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors2")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example2.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();
        final AggregatedHttpMessage response3 = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors2")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://notallowed.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();
        assertEquals(HttpStatus.OK, response.status());
        assertEquals(HttpStatus.OK, response2.status());
        assertEquals(HttpStatus.FORBIDDEN, response3.status());
    }

    // Makes sure if it uses a specified policy for specified origins.
    @Test
    public void testCorsDifferentPolicy() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();
        final AggregatedHttpMessage response2 = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example2.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();
        assertEquals(HttpStatus.OK, response.status());
        assertEquals(HttpStatus.OK, response2.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("http://example2.com",
                     response2.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("allow_request_header",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("allow_request_header2",
                     response2.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("expose_header_1,expose_header_2",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));
        assertEquals("expose_header_3,expose_header_4",
                     response2.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    @Test
    public void testCorsPreflight() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.OPTIONS, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        ).aggregate().get();
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("Hello CORS", response.headers().get(HttpHeaderNames.of("x-preflight-cors")));
    }

    @Test
    public void testCorsAllowed() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testCorsAccessControlHeaders() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.OPTIONS, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("GET,POST", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("allow_request_header",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
    }

    @Test
    public void testCorsExposeHeaders() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.com")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("allow_request_header",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("expose_header_1,expose_header_2",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    @Test
    public void testCorsForbidden() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory, server.uri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/cors")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, "http://example.org")
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")).aggregate().get();

        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
