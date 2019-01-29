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

import static org.assertj.core.api.Assertions.assertThat;
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
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorators;
import com.linecorp.armeria.testing.server.ServerRule;

public class HttpServerCorsTest {

    private static final ClientFactory clientFactory = ClientFactory.DEFAULT;

    @CorsDecorators(value = {
            @CorsDecorator(origins = "http://example.com", exposedHeaders = "expose_header_1")
    }, shortCircuit = true)
    private static class MyAnnotatedService {

        @Get("/index")
        @StatusCode(200)
        public void index() {}

        @Get("/dup_test")
        @StatusCode(200)
        @CorsDecorator(origins = "http://example2.com", exposedHeaders = "expose_header_2")
        public void duptest() {}
    }

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
                                      .preflightResponseHeader("x-preflight-cors", "Hello CORS", "Hello CORS2")
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
            final Object myAnnotatedService = new Object() {
                // We don't need to specify '@Options` annotation here to support a CORS preflight request.
                @Post("/post")
                @StatusCode(200)
                public void post() {}

                @Options("/options")
                @StatusCode(200)
                public void options() {}
            };
            sb.annotatedService("/cors4", myAnnotatedService,
                                CorsServiceBuilder.forOrigin("http://example.com")
                                                  .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                                                  .newDecorator());
            // No CORS decorator.
            sb.annotatedService("/cors5", myAnnotatedService);

            sb.annotatedService("/cors6", new Object() {

                @Get("/any/get")
                @CorsDecorator(
                        origins = "*", exposedHeaders = { "expose_header_1", "expose_header_2" },
                        allowedRequestHeaders = { "allow_request_1", "allow_request_2" },
                        allowedRequestMethods = HttpMethod.GET, maxAge = 3600,
                        preflightRequestHeaders = {
                                @AdditionalHeader(name = "x-preflight-cors",
                                        value = { "Hello CORS", "Hello CORS2" })
                        })
                public HttpResponse anyoneGet() {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Post("/one/post")
                @CorsDecorator(
                        origins = "http://example.com",
                        exposedHeaders = { "expose_header_1", "expose_header_2" },
                        allowedRequestMethods = HttpMethod.POST, credentialsAllowed = true,
                        allowedRequestHeaders = { "allow_request_1", "allow_request_2" }, maxAge = 1800,
                        preflightRequestHeaders = {
                                @AdditionalHeader(name = "x-preflight-cors", value = "Hello CORS")
                        })
                public HttpResponse onePolicyPost() {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Get("/multi/get")
                @CorsDecorator(
                        origins = "http://example.com", exposedHeaders = { "expose_header_1" },
                        allowedRequestMethods = HttpMethod.GET, credentialsAllowed = true)
                @CorsDecorator(
                        origins = "http://example2.com", exposedHeaders = { "expose_header_2" },
                        allowedRequestMethods = HttpMethod.GET, credentialsAllowed = true)
                public HttpResponse multiGet() {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.annotatedService("/cors7", new MyAnnotatedService());
        }
    };

    static HttpClient client() {
        return HttpClient.of(clientFactory, server.uri("/"));
    }

    static AggregatedHttpMessage request(HttpClient client, HttpMethod method, String path, String origin,
                                         String requestMethod) {
        return client.execute(
                HttpHeaders.of(method, path)
                           .set(HttpHeaderNames.ACCEPT, "utf-8")
                           .set(HttpHeaderNames.ORIGIN, origin)
                           .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, requestMethod)
        ).aggregate().join();
    }

    static AggregatedHttpMessage preflightRequest(HttpClient client, String path, String origin,
                                                  String requestMethod) {
        return request(client, HttpMethod.OPTIONS, path, origin, requestMethod);
    }

    @Test
    public void testCorsDecoratorAnnotation() {
        final HttpClient client = client();
        final AggregatedHttpMessage response = preflightRequest(client, "/cors6/any/get", "http://example.com",
                                                                "GET");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("allow_request_1,allow_request_2",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("*", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("3600", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE));

        assertThat(response.headers().getAll(HttpHeaderNames.of("x-preflight-cors"))).containsExactly(
                "Hello CORS", "Hello CORS2");

        final AggregatedHttpMessage response3 = request(client, HttpMethod.GET, "/cors6/multi/get",
                                                        "http://example.com", "GET");
        assertEquals("expose_header_1", response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));

        final AggregatedHttpMessage response4 = request(client, HttpMethod.GET, "/cors6/multi/get",
                                                        "http://example2.com", "GET");
        assertEquals("expose_header_2", response4.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));

        final AggregatedHttpMessage response5 = preflightRequest(client, "/cors7/index", "http://example.com",
                                                                 "GET");
        assertEquals(HttpStatus.OK, response5.status());

        final AggregatedHttpMessage response6 = request(client, HttpMethod.GET, "/cors7/index",
                                                        "http://example2.com", "GET");
        assertEquals(HttpStatus.FORBIDDEN, response6.status());
        final AggregatedHttpMessage response7 = request(client, HttpMethod.GET, "/cors7/dup_test",
                                                        "http://example2.com", "GET");
        assertEquals(HttpStatus.OK, response7.status());
        assertEquals("expose_header_2", response7.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

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
        final HttpClient client = client();
        final AggregatedHttpMessage response = preflightRequest(client, "/cors2", "null", "POST");
        assertEquals("null", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("GET", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("allow_request_header2",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
    }

    // Makes sure if an any origin supported CorsService works properly and it allows null origin too.
    @Test
    public void testCorsAnyOrigin() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = request(client, HttpMethod.POST, "/cors3", "http://example.com",
                                                       "POST");
        final AggregatedHttpMessage response2 = request(client, HttpMethod.POST, "/cors3", "null", "POST");
        final AggregatedHttpMessage response3 = preflightRequest(client, "/cors3", "http://example.com",
                                                                 "POST");
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
        final HttpClient client = client();
        final AggregatedHttpMessage response = request(client, HttpMethod.POST, "/cors2", "http://example.com",
                                                       "POST");
        final AggregatedHttpMessage response2 = request(client, HttpMethod.POST, "/cors2",
                                                        "http://example2.com", "POST");
        final AggregatedHttpMessage response3 = request(client, HttpMethod.POST, "/cors2",
                                                        "http://notallowed.com", "POST");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals(HttpStatus.OK, response2.status());
        assertEquals(HttpStatus.FORBIDDEN, response3.status());
    }

    // Makes sure if it uses a specified policy for specified origins.
    @Test
    public void testCorsDifferentPolicy() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = request(client, HttpMethod.POST, "/cors", "http://example.com",
                                                       "POST");
        final AggregatedHttpMessage response2 = request(client, HttpMethod.POST, "/cors", "http://example2.com",
                                                        "POST");

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
        final HttpClient client = client();
        final AggregatedHttpMessage response = preflightRequest(client, "/cors", "http://example.com", "POST");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("Hello CORS", response.headers().get(HttpHeaderNames.of("x-preflight-cors")));
    }

    @Test
    public void testCorsAllowed() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = request(client, HttpMethod.POST, "/cors", "http://example.com",
                                                       "POST");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testCorsAccessControlHeaders() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = preflightRequest(client, "/cors", "http://example.com", "POST");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("GET,POST", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("allow_request_header",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
    }

    @Test
    public void testCorsExposeHeaders() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = request(client, HttpMethod.POST, "/cors", "http://example.com",
                                                       "POST");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("http://example.com", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("allow_request_header",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("expose_header_1,expose_header_2",
                     response.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    @Test
    public void testCorsForbidden() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = request(client, HttpMethod.POST, "/cors", "http://example.org",
                                                       "POST");

        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testWorkingWithAnnotatedService() throws Exception {
        final HttpClient client = client();

        for (final String path : new String[] { "post", "options" }) {
            final AggregatedHttpMessage response = preflightRequest(client, "/cors4/" + path,
                                                                    "http://example.com", "POST");
            assertEquals(HttpStatus.OK, response.status());
            assertEquals("http://example.com",
                         response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
            assertEquals("GET,POST", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        }
    }

    @Test
    public void testNoCorsDecoratorForAnnotatedService() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = preflightRequest(client, "/cors5/post", "http://example.com",
                                                                "POST");
        assertEquals(HttpStatus.FORBIDDEN, response.status());
    }

    @Test
    public void testAnnotatedServiceHandlesOptions() throws Exception {
        final HttpClient client = client();
        final AggregatedHttpMessage response = preflightRequest(client, "/cors5/options", "http://example.com",
                                                                "POST");
        assertEquals(HttpStatus.OK, response.status());
    }
}
