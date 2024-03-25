/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContextPathTest {

    static HttpServiceWithRoutes serviceWithRoutes = new HttpServiceWithRoutes() {
        @Override
        public Set<Route> routes() {
            return ImmutableSet.of(Route.builder().path("/serviceWithRoutes1").build(),
                                   Route.builder().path("/serviceWithRoutes2").build());
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final String path = req.path();
            return HttpResponse.of(path.substring(path.lastIndexOf('/') + 1));
        }
    };

    static final Object annotatedService = new Object() {
        @Get("/annotated1")
        public HttpResponse get() {
            return HttpResponse.of("annotated1");
        }
    };

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // server builder context path
            sb.contextPath("/v1", "/v2")
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes)
              .and()
              // server builder
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes)
              // server decorator with context path
              .contextPath("/v5", "/v6")
              .service("/decorated1", (ctx, req) -> HttpResponse.of(500))
              .decorator("/decorated1", (delegate, ctx, req) -> HttpResponse.of("decorated1"))
              .routeDecorator()
              .path("/decorated2")
              .build((delegate, ctx, req) -> HttpResponse.of("decorated2"))
              .and()
              // server decorator
              .service("/decorated1", (ctx, req) -> HttpResponse.of(500))
              .decorator("/decorated1", (delegate, ctx, req) -> HttpResponse.of("decorated1"))
              .routeDecorator()
              .path("/decorated2")
              .build((delegate, ctx, req) -> HttpResponse.of("decorated2"))
              // virtual host service context path
              .virtualHost("foo.com")
              .contextPath("/v3", "/v4")
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes)
              .and()
              // virtual host decorator context path
              .contextPath("/v5", "/v6")
              .service("/decorated1", (ctx, req) -> HttpResponse.of(500))
              .decorator("/decorated1", (delegate, ctx, req) -> HttpResponse.of("decorated1"))
              .routeDecorator()
              .path("/decorated2")
              .build((delegate, ctx, req) -> HttpResponse.of("decorated2"))
              // virtual host
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes)
              // virtual host decorator
              .service("/decorated1", (ctx, req) -> HttpResponse.of(500))
              .decorator("/decorated1", (delegate, ctx, req) -> HttpResponse.of("decorated1"))
              .routeDecorator()
              .path("/decorated2")
              .build((delegate, ctx, req) -> HttpResponse.of("decorated2"));
            sb.decorator(LoggingService.newDecorator());
            sb.rejectedRouteHandler(RejectedRouteHandler.FAIL);
        }
    };

    @ParameterizedTest
    @ValueSource(strings = {"", "/v1", "/v2"})
    void testServerService(String contextPath) {
        final BlockingWebClient client = server.blockingWebClient();

        assertResult(client.get(contextPath + "/service1"), "service1");
        assertResult(client.get(contextPath + "/route1"), "route1");
        assertResult(client.get(contextPath + "/serviceWithRoutes1"), "serviceWithRoutes1");
        assertResult(client.get(contextPath + "/serviceWithRoutes2"), "serviceWithRoutes2");
        assertResult(client.get(contextPath + "/annotated1"), "annotated1");
        assertResult(client.get(contextPath + "/prefix/annotated1"), "annotated1");
        assertResult(client.get(contextPath + "/serviceUnder1/"), "serviceUnder1");
        assertResult(client.get(contextPath + "/serviceUnder2/serviceWithRoutes1"), "serviceWithRoutes1");
        assertResult(client.get(contextPath + "/serviceUnder2/serviceWithRoutes2"), "serviceWithRoutes2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/v3", "/v4"})
    void testVHostService(String contextPath) {
        final BlockingWebClient client =
                server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"));
        assertResult(client.get(contextPath + "/service1"), "service1");
        assertResult(client.get(contextPath + "/route1"), "route1");
        assertResult(client.get(contextPath + "/serviceWithRoutes1"), "serviceWithRoutes1");
        assertResult(client.get(contextPath + "/serviceWithRoutes2"), "serviceWithRoutes2");
        assertResult(client.get(contextPath + "/annotated1"), "annotated1");
        assertResult(client.get(contextPath + "/prefix/annotated1"), "annotated1");
        assertResult(client.get(contextPath + "/serviceUnder1/"), "serviceUnder1");
        assertResult(client.get(contextPath + "/serviceUnder2/serviceWithRoutes1"), "serviceWithRoutes1");
        assertResult(client.get(contextPath + "/serviceUnder2/serviceWithRoutes2"), "serviceWithRoutes2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/v5", "/v6"})
    void testServerDecorator(String contextPath) {
        final BlockingWebClient client = server.blockingWebClient();
        assertResult(client.get(contextPath + "/decorated1"), "decorated1");
        assertResult(client.get(contextPath + "/decorated2"), "decorated2");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "/v5", "/v6"})
    void testVHostDecorator(String contextPath) {
        final BlockingWebClient client =
                server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"));
        assertResult(client.get(contextPath + "/decorated1"), "decorated1");
        assertResult(client.get(contextPath + "/decorated2"), "decorated2");
    }

    @Test
    void invalidContextPath() {
        assertThatThrownBy(() -> Server.builder().contextPath())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one context path is required");

        assertThatThrownBy(() -> Server.builder().contextPath(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected: an absolute path starting with");

        assertThatThrownBy(() -> Server.builder().contextPath("/", "relative"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected: an absolute path starting with");
    }

    private static void assertResult(AggregatedHttpResponse res, String expectedContent) {
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo(expectedContent);
    }
}
