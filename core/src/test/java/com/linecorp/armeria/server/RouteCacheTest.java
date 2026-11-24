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

import static com.linecorp.armeria.server.RouteCache.wrapVirtualHostRouter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
              .get("/exact/items")
              .matchesParams("itemIds")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.service("/exact/items", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("/prefix/items")
              .matchesParams("itemIds")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/prefix/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("regex:/regex/items")
              .matchesParams("itemIds")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/regex/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("/excluded/items")
              .exclude(Route.builder()
                            .path("/excluded/items")
                            .matchesParams("skip"
                            ).build())
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/excluded/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route().get("/header-excluded/items")
              .exclude(Route.builder()
                            .path("/header-excluded/items")
                            .matchesHeaders("x-skip")
                            .build())
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/header-excluded/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("/exact/headers")
              .matchesHeaders("x-item-ids")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.service("/exact/headers", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));

            sb.route()
              .get("/header-prefix/items")
              .matchesHeaders("x-item-ids")
              .build((ctx, req) -> HttpResponse.of("items"));
            sb.serviceUnder("/header-prefix/", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));
        }
    };

    private static void assertRouteSelection(RequestHeaders matchingHeaders,
                                             RequestHeaders nonMatchingHeaders) {
        final BlockingWebClient client = server.blockingWebClient();
        // Start with a rejected predicate, which must not cause the fallback service to be cached.
        assertThat(client.execute(HttpRequest.of(nonMatchingHeaders)).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
        final AggregatedHttpResponse response = client.execute(HttpRequest.of(matchingHeaders));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("items");
    }

    private static VirtualHost newVirtualHost(Route conditionalRoute) {
        return Server.builder()
                     .service(conditionalRoute, (ctx, req) -> HttpResponse.of("items"))
                     .service("/items", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND))
                     .build()
                     .config()
                     .defaultVirtualHost();
    }

    private static Router<ServiceConfig> newRouter(VirtualHost virtualHost) {
        return Routers.routers(virtualHost.serviceConfigs(), null, null, ServiceConfig::route,
                               (route, existingRoute) -> {
                                   throw new AssertionError("Unexpected duplicate route: " + route);
                               }, false)
                      .get(0);
    }

    @ParameterizedTest
    @CsvSource({
            "/exact/items?itemIds=example, /exact/items",
            "/prefix/items?itemIds=example, /prefix/items",
            "/regex/items?itemIds=example, /regex/items",
            "/excluded/items, /excluded/items?skip"
    })
    void queryPredicateIsReevaluated(String matchingPath, String nonMatchingPath) {
        assertRouteSelection(RequestHeaders.of(HttpMethod.GET, matchingPath),
                             RequestHeaders.of(HttpMethod.GET, nonMatchingPath));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/exact/headers", "/header-prefix/items" })
    void headerPredicateIsReevaluated(String path) {
        assertRouteSelection(RequestHeaders.of(HttpMethod.GET, path, "x-item-ids", "example"),
                             RequestHeaders.of(HttpMethod.GET, path));
    }

    @Test
    void excludedHeaderPredicateIsReevaluated() {
        assertRouteSelection(RequestHeaders.of(HttpMethod.GET, "/header-excluded/items"),
                             RequestHeaders.of(HttpMethod.GET, "/header-excluded/items", "x-skip", "true"));
    }

    @Test
    void cachesRouteWhenPredicateIsNotEvaluated() {
        final Route conditionalRoute = Route.builder()
                                            .path("/items")
                                            .methods(HttpMethod.GET)
                                            .matchesParams("itemIds")
                                            .build();
        final VirtualHost virtualHost = newVirtualHost(conditionalRoute);
        @SuppressWarnings("unchecked")
        final Router<ServiceConfig> delegate = mock(Router.class, delegatesTo(newRouter(virtualHost)));
        final Router<ServiceConfig> router = wrapVirtualHostRouter(delegate, ImmutableSet.of(conditionalRoute));

        for (int i = 0; i < 2; i++) {
            final RoutingContext ctx = RoutingContextTest.create(virtualHost, "/items", null)
                                                         .withMethod(HttpMethod.POST);
            assertThat(router.find(ctx).route().methods()).contains(HttpMethod.POST);
            assertThat(ctx.isRouteSelectionCacheable()).isTrue();
        }
        verify(delegate).find(any(RoutingContext.class));
    }

    @Test
    void customRouteExplicitlyMarksSelectionUncacheable() {
        final Route pathRoute = Route.builder()
                                     .path("/items")
                                     .methods(HttpMethod.GET)
                                     .build();
        final Route customRoute = mock(Route.class, delegatesTo(pathRoute));
        doReturn(customRoute).when(customRoute).withPrefix("/");
        doReturn(false).when(customRoute).isCacheable();
        doAnswer(invocation -> {
            final RoutingContext ctx = invocation.getArgument(0);
            ctx.markRouteSelectionUncacheable();
            return ctx.params().contains("itemIds") ? pathRoute.apply(ctx, false)
                                                    : RoutingResult.empty();
        }).when(customRoute).apply(any(RoutingContext.class), anyBoolean());
        final VirtualHost virtualHost = newVirtualHost(customRoute);
        final Router<ServiceConfig> router = wrapVirtualHostRouter(newRouter(virtualHost),
                                                                   ImmutableSet.of(customRoute));

        final RoutingContext rejected = RoutingContextTest.create(virtualHost, "/items", null);
        assertThat(router.find(rejected).route()).isNotSameAs(customRoute);
        assertThat(rejected.isRouteSelectionCacheable()).isFalse();
        final RoutingContext matched = RoutingContextTest.create(virtualHost, "/items", "itemIds=123");
        assertThat(router.find(matched).route()).isSameAs(customRoute);
    }

    @Test
    void generatedRedirectRouteMarksRejectedPredicate() {
        final HttpService otherService = (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND);
        final VirtualHost virtualHost = Server.builder()
                                              .route()
                                              .get("/items/")
                                              .matchesParams("itemIds")
                                              .build((ctx, req) -> HttpResponse.of("items"))
                                              .serviceUnder("/", otherService)
                                              .build()
                                              .config()
                                              .defaultVirtualHost();
        final RoutingContext rejected = RoutingContextTest.create(virtualHost, "/items", null);
        assertThat(virtualHost.findServiceConfig(rejected).value().service()).isSameAs(otherService);
        assertThat(rejected.isRouteSelectionCacheable()).isFalse();

        final RoutingContext matched = RoutingContextTest.create(virtualHost, "/items", "itemIds=123");
        assertThat(virtualHost.findServiceConfig(matched).value().service())
                .isSameAs(FallbackService.INSTANCE);
    }
}
