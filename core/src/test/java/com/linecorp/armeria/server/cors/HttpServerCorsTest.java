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

import java.util.Arrays;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorators;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerCorsTest {

    private static final ClientFactory clientFactory = ClientFactory.ofDefault();

    @CorsDecorators(value = {
            @CorsDecorator(origins = "http://example.com", exposedHeaders = "expose_header_1")
    }, shortCircuit = true)
    private static class MyAnnotatedService {

        @Get("/index")
        @StatusCode(200)
        public void index() {}

        @ConsumesJson
        @Get("/dup_test")
        @StatusCode(200)
        @CorsDecorator(origins = "http://example2.com", exposedHeaders = "expose_header_2",
                       allowedRequestHeaders = "content-type")
        public void dupTest() {}
    }

    @CorsDecorator(
            origins = "http://example.com",
            pathPatterns = "glob:/**/configured",
            allowedRequestMethods = HttpMethod.GET
    )
    private static class MyAnnotatedService2 {
        @Get("/configured")
        public void configured() {}

        @Get("/not_configured")
        public void notConfigured() {}
    }

    @CorsDecorator(
            origins = "http://example.com",
            allowedRequestMethods = HttpMethod.GET,
            allowAllRequestHeaders = true
    )
    private static class MyAnnotatedService3 {
        @Get("/index")
        @StatusCode(200)
        public void index() {}
    }

    @CorsDecorator(
            originRegexes = "http:\\/\\/example.*",
            allowedRequestMethods = HttpMethod.GET
    )
    private static class MyAnnotatedService4 {
        @Get("/index")
        @StatusCode(200)
        public void index() {}
    }

    @CorsDecorator(
            origins = "http://armeria.com",
            originRegexes = { "http:\\/\\/line.*", "http:\\/\\/test.*" },
            allowedRequestMethods = HttpMethod.GET
    )
    private static class MyAnnotatedService5 {
        @Get("/index")
        @StatusCode(200)
        public void index() {}
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
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
                    CorsService.builder("http://example.com")
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
                    CorsService.builder("http://example.com")
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
                    CorsService.builderForAnyOrigin()
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
                                CorsService.builder("http://example.com")
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
                @CorsDecorator(origins = "http://example.com", exposedHeaders = "expose_header_1",
                               allowedRequestMethods = HttpMethod.GET, credentialsAllowed = true)
                @CorsDecorator(origins = "http://example2.com", exposedHeaders = "expose_header_2",
                               allowedRequestMethods = HttpMethod.GET, credentialsAllowed = true)
                public HttpResponse multiGet() {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.annotatedService("/cors7", new MyAnnotatedService());

            sb.annotatedService("/cors8", new Object() {
                @Get("/movies")
                public void titles() {}

                @Post("/movies/{title}")
                public void addMovie(@Param String title) {}

                @Get("/movies/{title}/actors")
                public void actors(@Param String title) {}
            }, CorsService.builder("http://example.com")
                          // Note that configuring mappings for specific paths first because the
                          // policies will be visited in the configured order.
                          .route("glob:/**/actors")
                          .allowRequestMethods(HttpMethod.GET, HttpMethod.POST)
                          .andForOrigin("http://example.com")
                          .route("/cors8/movies/{title}")
                          .allowRequestMethods(HttpMethod.POST)
                          .andForOrigin("http://example.com")
                          .route("prefix:/cors8")
                          .allowRequestMethods(HttpMethod.GET)
                          .and()
                          .newDecorator());

            sb.annotatedService("/cors9", new Object() {
                @Get("/movies")
                public void titles() {}

                @Post("/movies/{title}")
                public void addMovie(@Param String title) {}

                @Get("/movies/{title}/actors")
                public void actors(@Param String title) {}
            }, CorsService.builder("http://example.com")
                          // Note that this prefix path mapping is configured first, so all preflight
                          // requests with a path starting with "/cors9/" will be matched.
                          .route("prefix:/cors9")
                          .allowRequestMethods(HttpMethod.GET)
                          .andForOrigin("http://example.com")
                          .route("/cors9/movies/{title}")
                          .allowRequestMethods(HttpMethod.POST)
                          .and()
                          .newDecorator());

            sb.annotatedService("/cors10", new MyAnnotatedService2());

            // CORS decorator as a route decorator & not bound for OPTIONS.
            sb.route().get("/cors11/get")
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.routeDecorator().pathPrefix("/cors11")
              .build(CorsService.builder("http://example.com")
                                .shortCircuit()
                                .allowRequestMethods(HttpMethod.GET)
                                .newDecorator());

            // No CORS decorator & not bound for OPTIONS.
            sb.route().get("/cors12/get")
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK));

            sb.service("/cors13", myService.decorate(
                    CorsService.builder("http://example.com")
                               .allowRequestMethods(HttpMethod.GET)
                               .allowAllRequestHeaders(true)
                               .newDecorator()));

            sb.annotatedService("/cors14", new MyAnnotatedService3());

            sb.service("/cors15", myService.decorate(
                    CorsService.builderForOriginRegex("^http:\\/\\/.*example.com$")
                               .shortCircuit()
                               .allowRequestMethods(HttpMethod.GET)
                               .newDecorator()));

            sb.annotatedService("/cors16", new MyAnnotatedService4());

            sb.annotatedService("/cors17", new MyAnnotatedService5());

            sb.service("/cors18", myService.decorate(
                    CorsService.builder(origin -> origin.contains("example") || origin.contains("line"))
                               .shortCircuit()
                               .allowRequestMethods(HttpMethod.GET)
                               .newDecorator()));

            sb.annotatedService("/cors19", new Object() {
                @Get("/index1")
                public void index1() {}

                @Post("/index2")
                public void index2() {}

                @Delete("/index3")
                public void index3() {}
            }, CorsService.builderForOriginRegex("^http:\\/\\/example.*")
                          .route("/cors19/index1")
                          .allowRequestMethods(HttpMethod.GET)
                          .andForOriginRegex(Pattern.compile(".*line.*"))
                          .route("/cors19/index2")
                          .allowRequestMethods(HttpMethod.POST)
                          .and()
                          .andForOrigin((origin) -> origin.contains("armeria"))
                          .route("/cors19/index3")
                          .allowRequestMethods(HttpMethod.DELETE)
                          .and()
                          .newDecorator());
        }
    };

    static WebClient client() {
        return WebClient.builder(server.httpUri())
                        .factory(clientFactory)
                        .build();
    }

    static AggregatedHttpResponse request(WebClient client, HttpMethod method, String path, String origin,
                                          String requestMethod) {
        return client.execute(
                RequestHeaders.of(method, path,
                                  HttpHeaderNames.ACCEPT, "utf-8",
                                  HttpHeaderNames.ORIGIN, origin,
                                  HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, requestMethod)
        ).aggregate().join();
    }

    static AggregatedHttpResponse preflightRequest(WebClient client, String path, String origin,
                                                   String requestMethod) {
        return request(client, HttpMethod.OPTIONS, path, origin, requestMethod);
    }

    @Test
    void testCorsDecoratorAnnotation() {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors6/any/get", "http://example.com",
                                                                 "GET");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("allow_request_1,allow_request_2");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("*");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE))
                .isEqualTo("3600");

        assertThat(response.headers().getAll(HttpHeaderNames.of("x-preflight-cors"))).containsExactly(
                "Hello CORS", "Hello CORS2");

        final AggregatedHttpResponse response3 = request(client, HttpMethod.GET, "/cors6/multi/get",
                                                         "http://example.com", "GET");
        assertThat(response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo("expose_header_1");

        final AggregatedHttpResponse response4 = request(client, HttpMethod.GET, "/cors6/multi/get",
                                                         "http://example2.com", "GET");
        assertThat(response4.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo("expose_header_2");

        final AggregatedHttpResponse response5 = preflightRequest(client, "/cors7/index", "http://example.com",
                                                                  "GET");
        assertThat(response5.status()).isEqualTo(HttpStatus.OK);

        final AggregatedHttpResponse response6 = request(client, HttpMethod.GET, "/cors7/index",
                                                         "http://example2.com", "GET");
        assertThat(response6.status()).isEqualTo(HttpStatus.FORBIDDEN);
        final AggregatedHttpResponse response7 = request(client, HttpMethod.GET, "/cors7/dup_test",
                                                         "http://example2.com", "GET");
        assertThat(response7.status()).isEqualTo(HttpStatus.OK);
        assertThat(response7.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo("expose_header_2");
    }

    // Makes sure if it throws an Exception when an improper setting is set.
    @Test
    void testCorsBuilderException() {
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().maxAge(-1)).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().allowNullOrigin()).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().shortCircuit()).isInstanceOf(
                IllegalStateException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().allowRequestHeaders("", null, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().allowRequestMethods(HttpMethod.GET, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> CorsService.builderForAnyOrigin().preflightResponseHeader("header", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().preflightResponseHeader("header", Arrays
                .asList("11", null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().preflightResponseHeader("123")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().preflightResponseHeader("123",
                                                                                           ImmutableList.of()));
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().exposeHeaders()).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().allowRequestMethods()).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> CorsService.builderForAnyOrigin().allowRequestHeaders()).isInstanceOf(
                IllegalArgumentException.class);

        // Ensure double decoration is prohibited.
        assertThatThrownBy(() -> {
            final Function<? super HttpService, CorsService> decorator =
                    CorsService.builderForAnyOrigin().newDecorator();
            final HttpService service = (ctx, req) -> HttpResponse.of("OK");
            service.decorate(decorator).decorate(decorator);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("decorated with a CorsService already");
    }

    // Makes sure if null origin supported CorsService works properly and it finds the CORS policy
    // which supports null origin.
    @Test
    void testCorsNullOrigin() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors2", "null", "POST");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("null");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("allow_request_header2");
    }

    // Makes sure if an any origin supported CorsService works properly and it allows null origin too.
    @Test
    void testCorsAnyOrigin() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = request(client, HttpMethod.POST, "/cors3", "http://example.com",
                                                        "POST");
        final AggregatedHttpResponse response2 = request(client, HttpMethod.POST, "/cors3", "null", "POST");
        final AggregatedHttpResponse response3 = preflightRequest(client, "/cors3", "http://example.com",
                                                                  "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.status()).isEqualTo(HttpStatus.OK);
        assertThat(response3.status()).isEqualTo(HttpStatus.OK);
        assertThat(response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");
        assertThat(response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS))
                .isEqualTo("GET,POST");
        assertThat(response3.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("allow_request_header");
        assertThat(response3.headers().get(HttpHeaderNames.of("x-preflight-cors")))
                .isEqualTo("Hello CORS");
    }

    // Makes sure if shortCircuit works properly.
    @Test
    void testCorsShortCircuit() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = request(client, HttpMethod.POST, "/cors2", "http://example.com",
                                                        "POST");
        final AggregatedHttpResponse response2 = request(client, HttpMethod.POST, "/cors2",
                                                         "http://example2.com", "POST");
        final AggregatedHttpResponse response3 = request(client, HttpMethod.POST, "/cors2",
                                                         "http://notallowed.com", "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.status()).isEqualTo(HttpStatus.OK);
        assertThat(response3.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Makes sure if it uses a specified policy for specified origins.
    @Test
    void testCorsDifferentPolicy() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = request(client, HttpMethod.POST, "/cors", "http://example.com",
                                                        "POST");
        final AggregatedHttpResponse response2 = request(client, HttpMethod.POST, "/cors",
                                                         "http://example2.com", "POST");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(response2.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example2.com");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("allow_request_header");
        assertThat(response2.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("allow_request_header2");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo("expose_header_1,expose_header_2");
        assertThat(response2.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo("expose_header_3,expose_header_4");
    }

    @Test
    void testCorsPreflight() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors", "http://example.com", "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(response.headers().get(HttpHeaderNames.of("x-preflight-cors")))
                .isEqualTo("Hello CORS");
    }

    @Test
    void testCorsPreflightWithQueryParams() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors?a=b", "http://example.com",
                                                                 "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(response.headers().get(HttpHeaderNames.of("x-preflight-cors")))
                .isEqualTo("Hello CORS");
    }

    @Test
    void testCorsAllowed() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = request(client, HttpMethod.POST, "/cors", "http://example.com",
                                                        "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
    }

    @Test
    void testCorsAccessControlHeaders() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors", "http://example.com", "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS))
                .isEqualTo("GET,POST");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("allow_request_header");
    }

    @Test
    void testCorsExposeHeaders() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = request(client, HttpMethod.POST, "/cors", "http://example.com",
                                                        "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("allow_request_header");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isEqualTo("expose_header_1,expose_header_2");
    }

    @Test
    void testCorsForbidden() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = request(client, HttpMethod.POST, "/cors", "http://example.org",
                                                        "POST");

        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void testWorkingWithAnnotatedService() throws Exception {
        final WebClient client = client();

        for (final String path : new String[] { "post", "options" }) {
            final AggregatedHttpResponse response = preflightRequest(client, "/cors4/" + path,
                                                                     "http://example.com", "POST");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("http://example.com");
            assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS))
                    .isEqualTo("GET,POST");
        }
    }

    @Test
    void testNoCorsDecoratorForAnnotatedService() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors5/post", "http://example.com",
                                                                 "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void testAnnotatedServiceHandlesOptions() throws Exception {
        final WebClient client = client();
        final AggregatedHttpResponse response = preflightRequest(client, "/cors5/options", "http://example.com",
                                                                 "POST");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testRoute() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = preflightRequest(client, "/cors8/movies", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");

        res = preflightRequest(client, "/cors8/movies/InfinityWar", "http://example.com", "POST");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("POST");

        res = preflightRequest(client, "/cors8/movies/InfinityWar/actors", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET,POST");
    }

    @Test
    void testRoute_order() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = preflightRequest(client, "/cors9/movies", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");

        res = preflightRequest(client, "/cors9/movies/InfinityWar", "http://example.com", "POST");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");

        res = preflightRequest(client, "/cors9/movies/InfinityWar/actors", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");
    }

    @Test
    void testRoute_annotated() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = preflightRequest(client, "/cors10/configured", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");

        res = preflightRequest(client, "/cors10/not_configured", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isNull();
    }

    /**
     * If CORS was configured as a route decorator and there's no binding for OPTIONS method,
     * the server's fallback service decorated with the CORS decorator will be matched and thus
     * must respond with a CORS response.
     */
    @Test
    void testCorsWithPartialBindingAndRouteDecorator() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = preflightRequest(client, "/cors11/get", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");

        // GET must be allowed.
        res = request(client, HttpMethod.GET, "/cors11/get", "http://example.com", "GET");
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");

        // Other methods must be disallowed.
        res = request(client, HttpMethod.GET, "/cors11/get", "http://notallowed.com", "GET");
        assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
    }

    /**
     * If no CORS was configured and there's no binding for OPTIONS method, the server's fallback service will
     * be matched and the service with partial binding must not be invoked.
     */
    @Test
    void testNoCorsWithPartialBinding() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        // A simple OPTIONS request, which should fall back.
        res = client.options("/cors12/get").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        // A CORS preflight request, which should fall back as well.
        res = preflightRequest(client, "/cors12/get", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.FORBIDDEN);
        // .. but will not contain CORS headers.
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isNull();
    }

    @Test
    void testAllowAllHeaders() {
        final WebClient client = client();

        HttpRequest preflightReq = HttpRequest.of(
                RequestHeaders.of(HttpMethod.OPTIONS, "/cors13",
                                  HttpHeaderNames.ORIGIN, "http://example.com",
                                  HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET",
                                  HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "foo,bar"));
        AggregatedHttpResponse res = client.execute(preflightReq).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS)).isEqualTo("foo,bar");

        preflightReq = HttpRequest.of(
                RequestHeaders.of(HttpMethod.OPTIONS, "/cors13",
                                  HttpHeaderNames.ORIGIN, "http://example.com",
                                  HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET"));
        res = client.execute(preflightReq).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS)).isNull();
    }

    @Test
    void testAnnotatedServiceAllowAllHeaders() {
        final WebClient client = client();
        final HttpRequest preflightReq = HttpRequest.of(
                RequestHeaders.of(HttpMethod.OPTIONS, "/cors14/index",
                                  HttpHeaderNames.ORIGIN, "http://example.com",
                                  HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET",
                                  HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "foo,bar"));
        final AggregatedHttpResponse res = client.execute(preflightReq).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS)).isEqualTo("foo,bar");
    }

    @Test
    public void testBuilderForOriginRegex() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = request(client, HttpMethod.GET, "/cors15", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        res = request(client, HttpMethod.GET, "/cors15", "http://1.example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        res = request(client, HttpMethod.GET, "/cors15", "http://2.example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        res = request(client, HttpMethod.GET, "/cors15", "http://invalid.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    public void testAnnotatedServiceOriginRegex() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = request(client, HttpMethod.GET, "/cors16/index", "http://example.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        res = request(client, HttpMethod.GET, "/cors16/index", "http://example1.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example1.com");
        res = request(client, HttpMethod.GET, "/cors16/index", "http://example.org", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.org");

        res = request(client, HttpMethod.GET, "/cors16/index", "http://invalid.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    public void testAnnotatedServiceOriginAndOriginRegex() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = request(client, HttpMethod.GET, "/cors17/index", "http://armeria.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://armeria.com");
        res = request(client, HttpMethod.GET, "/cors17/index", "http://line1.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://line1.com");
        res = request(client, HttpMethod.GET, "/cors17/index", "http://line2.org", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://line2.org");
        res = request(client, HttpMethod.GET, "/cors17/index", "http://test.org", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://test.org");

        res = request(client, HttpMethod.GET, "/cors17/index", "http://invalid.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    public void testOriginPredicate() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = request(client, HttpMethod.GET, "/cors18", "http://example.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        res = request(client, HttpMethod.GET, "/cors18", "http://line.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://line.com");
        res = request(client, HttpMethod.GET, "/cors18", "http://example.line.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.line.com");

        res = request(client, HttpMethod.GET, "/cors18", "http://invalid.com", "GET");
        assertThat(res.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    public void testOriginRegexAndPredicatePerRoute() {
        final WebClient client = client();
        AggregatedHttpResponse res;

        res = preflightRequest(client, "/cors19/index1", "http://example.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://example.com");
        res = preflightRequest(client, "/cors19/index1", "http://invalid.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isNull();

        res = preflightRequest(client, "/cors19/index2", "http://line.com", "POST");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("POST");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://line.com");
        res = preflightRequest(client, "/cors19/index2", "http://invalid.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isNull();

        res = preflightRequest(client, "/cors19/index3", "http://armeria.com", "DELETE");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("DELETE");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://armeria.com");
        res = preflightRequest(client, "/cors19/index3", "http://invalid.com", "DELETE");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)).isNull();
    }

    @Test
    void caseInsensitiveOriginCheck() {
        final WebClient client = client();
        final AggregatedHttpResponse res = preflightRequest(client, "/cors", "http://EXAMPLE.com", "GET");
        assertThat(res.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://EXAMPLE.com");
    }
}
