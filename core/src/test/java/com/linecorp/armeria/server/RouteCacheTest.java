/*
 * Copyright 2026 LINE Corporation
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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RouteCacheTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route()
              .get("/api/v1/items")
              .matchesParams("itemIds")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.route()
              .get("/api/v1/items/headers")
              .matchesHeaders("x-item-ids")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.service("/api/v1/items", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));
            sb.service("/api/v1/items/headers", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("/prefix/items")
              .matchesParams("itemIds")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.route()
              .get("/prefix/items/headers")
              .matchesHeaders("x-item-ids")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/prefix/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("/excluded/items")
              .exclude(Route.builder()
                            .path("/excluded/items")
                            .matchesParams("skip")
                            .build())
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/excluded/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("regex:/regex/items")
              .matchesParams("itemIds")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/regex/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));
        }
    };

    private static void assertRouteSelection(RequestHeaders matchingHeaders,
                                             RequestHeaders nonMatchingHeaders) {
        final BlockingWebClient client = server.blockingWebClient();

        final AggregatedHttpResponse before = client.execute(HttpRequest.of(matchingHeaders));
        assertThat(before.status()).isEqualTo(HttpStatus.OK);
        assertThat(before.contentUtf8()).isEqualTo("items");

        assertThat(client.execute(HttpRequest.of(nonMatchingHeaders)).status()).isEqualTo(HttpStatus.NOT_FOUND);

        final AggregatedHttpResponse after = client.execute(HttpRequest.of(matchingHeaders));
        assertThat(after.status()).isEqualTo(HttpStatus.OK);
        assertThat(after.contentUtf8()).isEqualTo("items");
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/items?itemIds=example, /api/v1/items",
            "/prefix/items?itemIds=example, /prefix/items",
            "/regex/items?itemIds=example, /regex/items",
            "/excluded/items, /excluded/items?skip"
    })
    void queryPredicateIsReevaluatedForSubsequentRequests(String matchingPath, String nonMatchingPath) {
        assertRouteSelection(RequestHeaders.of(HttpMethod.GET, matchingPath),
                             RequestHeaders.of(HttpMethod.GET, nonMatchingPath));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/items/headers", "/prefix/items/headers" })
    void headerPredicateIsReevaluatedForSubsequentRequests(String path) {
        assertRouteSelection(RequestHeaders.of(HttpMethod.GET, path, "x-item-ids", "example"),
                             RequestHeaders.of(HttpMethod.GET, path));
    }

    @Test
    void cachesRouteWhenPredicateIsNotEvaluated() {
        final Route conditionalRoute = Route.builder()
                                            .path("/items")
                                            .methods(HttpMethod.GET)
                                            .matchesParams("itemIds")
                                            .build();
        final HttpService otherService = (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND);
        final VirtualHost virtualHost = Server.builder()
                                              .service(conditionalRoute, (ctx, req) -> HttpResponse.of("items"))
                                              .service("/items", otherService)
                                              .build()
                                              .config()
                                              .defaultVirtualHost();
        final Router<ServiceConfig> uncachedRouter =
                Routers.routers(virtualHost.serviceConfigs(), null, null, ServiceConfig::route,
                                (route, existingRoute) -> {
                                    throw new AssertionError("Unexpected duplicate route: " + route);
                                }, false)
                       .get(0);
        @SuppressWarnings("unchecked")
        final Router<ServiceConfig> delegate = mock(Router.class, delegatesTo(uncachedRouter));
        final Router<ServiceConfig> router =
                RouteCache.wrapVirtualHostRouter(delegate, ImmutableSet.of(conditionalRoute));

        final RoutingContext routingCtx1 = RoutingContextTest.create(virtualHost, "/items", null)
                                                             .withMethod(HttpMethod.POST);
        assertThat(router.find(routingCtx1).value().service()).isSameAs(otherService);
        verify(delegate).find(any(RoutingContext.class));

        final RoutingContext routingCtx2 = RoutingContextTest.create(virtualHost, "/items", null)
                                                             .withMethod(HttpMethod.POST);
        assertThat(router.find(routingCtx2).value().service()).isSameAs(otherService);
        verify(delegate, times(1)).find(any(RoutingContext.class));
    }
}
