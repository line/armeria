/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.RoutingContextTest.virtualHost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;

class RouterTest {

    private static final BiConsumer<Route, Route> REJECT = (a, b) -> {
        throw new IllegalStateException("duplicate route: " + a + " vs. " + b);
    };

    @Test
    void testRouters() {
        final List<Route> routes = Lists.newArrayList(
                Route.builder().path("exact:/a").build(),         // router 1
                Route.builder().path("/b/{var}").build(),
                Route.builder().path("prefix:/c").build(),
                Route.builder().path("regex:/d([^/]+)").build(),  // router 2
                Route.builder().path("glob:/e/**/z").build(),
                Route.builder().path("exact:/f").build(),         // router 3
                Route.builder().path("/g/{var}").build(),
                Route.builder().path("glob:/h/**/z").build(),     // router 4
                Route.builder().path("prefix:/i").build()         // router 5
        );
        final List<Router<Route>> routers =
                Routers.routers(routes, null, null, Function.identity(), REJECT, false);
        assertThat(routers).hasSize(5);

        // Map of a path string and a router index
        final List<Entry<String, Integer>> args = Lists.newArrayList(
                Maps.immutableEntry("/a", 0),
                Maps.immutableEntry("/b/1", 0),
                Maps.immutableEntry("/c/1", 0),
                Maps.immutableEntry("/dxxx/", 1),
                Maps.immutableEntry("/e/1/2/3/z", 1),
                Maps.immutableEntry("/f", 2),
                Maps.immutableEntry("/g/1", 2),
                Maps.immutableEntry("/h/1/2/3/z", 3),
                Maps.immutableEntry("/i/1/2/3", 4)
        );

        args.forEach(entry -> {
            for (int i = 0; i < 5; i++) {
                final Routed<Route> result = routers.get(i).find(routingCtx(entry.getKey()));
                assertThat(result.isPresent()).isEqualTo(i == entry.getValue());
            }
        });
    }

    @ParameterizedTest
    @MethodSource("generateRouteMatchData")
    void testFindAllMatchedRouters(String path, int expectForFind, List<Integer> expectForFindAll) {
        final List<Route> routes = Lists.newArrayList(
                Route.builder().path("prefix:/a").build(),
                Route.builder().path("/a/{var}").build(),
                Route.builder().path("prefix:/c").build(),
                Route.builder().path("regex:/d/([^/]+)").build(),
                Route.builder().path("glob:/d/**").build(),
                Route.builder().path("exact:/e").build(),
                Route.builder().path("glob:/h/**/z").build(),
                Route.builder().path("prefix:/h").build()
        );
        final List<Router<Route>> routers =
                Routers.routers(routes, null, null, Function.identity(), REJECT, false);
        final CompositeRouter<Route, Route> router = new CompositeRouter<>(routers, Function.identity());
        final RoutingContext routingCtx = routingCtx(path);
        assertThat(router.find(routingCtx).route()).isEqualTo(routes.get(expectForFind));

        final List<Route> matched = router.findAll(routingCtx)
                                          .stream().map(Routed::route).collect(toImmutableList());
        final List<Route> expected = expectForFindAll.stream().map(routes::get).collect(toImmutableList());
        assertThat(matched).containsAll(expected);
    }

    private static DefaultRoutingContext routingCtx(String path) {
        return new DefaultRoutingContext(virtualHost(), "example.com",
                                         RequestHeaders.of(HttpMethod.GET, path),
                                         RequestTarget.forServer(path), RoutingStatus.OK, SessionProtocol.H2C);
    }

    static Stream<Arguments> generateRouteMatchData() {
        return Stream.of(
                Arguments.of("/a/1", 1, ImmutableList.of(0, 1)),
                Arguments.of("/c/1", 2, ImmutableList.of(2)),
                Arguments.of("/d/12", 3, ImmutableList.of(3, 4)),
                Arguments.of("/e", 5, ImmutableList.of(5)),
                Arguments.of("/h/1/2/3/z", 6, ImmutableList.of(6, 7))
        );
    }

    @Test
    void duplicateRoutes() {
        // Simple cases
        testDuplicateRoutes(Route.builder().path("exact:/a").build(),
                            Route.builder().path("exact:/a").build());
        testDuplicateRoutes(Route.builder().path("exact:/a").build(),
                            Route.builder().path("/a").build());
        testDuplicateRoutes(Route.builder().path("prefix:/").build(),
                            Route.ofCatchAll());
    }

    /**
     * Should detect the duplicates even if the mappings are split into more than one router.
     */
    @Test
    void duplicateMappingsWithRegex() {
        // Ensure that 3 routers are created first really.
        assertThat(Routers.routers(ImmutableList.of(Route.builder().path("/foo/:bar").build(),
                                                    Route.builder().regex("not-trie-compatible").build(),
                                                    Route.builder().path("/bar/:baz").build()),
                                   null, null, Function.identity(), REJECT, false)).hasSize(3);

        testDuplicateRoutes(Route.builder().path("/foo/:bar").build(),
                            Route.builder().regex("not-trie-compatible").build(),
                            Route.builder().path("/foo/:qux").build());
    }

    @Test
    void duplicatePathWithHeaders() {
        // Duplicate if supported methods overlap.
        testDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.GET).build(),
                            Route.builder().path("/foo").methods(HttpMethod.GET, HttpMethod.POST).build());

        // Duplicate if supported methods overlap.
        testDuplicateRoutes(Route.builder().path("/foo").build(), // This route contains all methods.
                            Route.builder().path("/foo").methods(HttpMethod.GET).build());

        testNonDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.GET).build(),
                               Route.builder().path("/foo").methods(HttpMethod.POST).build());

        // Duplicate if consume types overlap.
        testDuplicateRoutes(Route.builder()
                                 .path("/foo")
                                 .methods(HttpMethod.POST)
                                 .consumes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                                 .build(),
                            Route.builder()
                                 .path("/foo")
                                 .methods(HttpMethod.POST)
                                 .consumes(MediaType.JSON_UTF_8)
                                 .build());

        testNonDuplicateRoutes(Route.builder()
                                    .path("/foo")
                                    .methods(HttpMethod.POST)
                                    .consumes(MediaType.PLAIN_TEXT_UTF_8)
                                    .build(),
                               Route.builder()
                                    .path("/foo")
                                    .methods(HttpMethod.POST)
                                    .consumes(MediaType.JSON_UTF_8)
                                    .build());

        // Duplicate if produce types overlap.
        testDuplicateRoutes(Route.builder()
                                 .path("/foo")
                                 .methods(HttpMethod.POST)
                                 .produces(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                                 .build(),
                            Route.builder()
                                 .path("/foo")
                                 .methods(HttpMethod.POST)
                                 .produces(MediaType.PLAIN_TEXT_UTF_8)
                                 .build());

        testNonDuplicateRoutes(Route.builder()
                                    .path("/foo")
                                    .methods(HttpMethod.POST)
                                    .produces(MediaType.PLAIN_TEXT_UTF_8)
                                    .build(),
                               Route.builder()
                                    .path("/foo")
                                    .methods(HttpMethod.POST)
                                    .produces(MediaType.JSON_UTF_8)
                                    .build());
    }

    @Test
    void duplicateWithPredicates() {
        // Duplicate if matches params overlap.
        testDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.POST)
                                 .matchesParams("a!=b||b=a", "foo")
                                 .build(),
                            Route.builder().path("/foo").methods(HttpMethod.POST)
                                 .matchesParams(" a!=b || b=a")
                                 .build());
        // Non-duplicate
        testNonDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.POST)
                                 .matchesParams("a!=b||b=c")
                                 .build(),
                            Route.builder().path("/foo").methods(HttpMethod.POST)
                                 .matchesParams("a!=b||b=a")
                                 .build());
        // Non-duplicate even if matches params overlap when the custom predicate is specified.
        testNonDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.POST)
                                    .matchesParams("foo", p -> true)
                                    .build(),
                               Route.builder().path("/foo").methods(HttpMethod.POST)
                                    .matchesParams("foo")
                                    .build());

        // Duplicate if matches headers overlap.
        testDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.POST)
                                 .matchesHeaders("foo", "bar")
                                 .build(),
                            Route.builder().path("/foo").methods(HttpMethod.POST)
                                 .matchesHeaders("foo")
                                 .build());
        // Non-duplicate
        testNonDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.POST)
                                    .matchesHeaders("foo")
                                    .build(),
                               Route.builder().path("/foo").methods(HttpMethod.POST)
                                    .matchesHeaders("foo1")
                                    .build());
        // Non-duplicate even if matches headers overlap when the custom predicate is specified.
        testNonDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.POST)
                                    .matchesHeaders("foo", p -> true)
                                    .build(),
                               Route.builder().path("/foo").methods(HttpMethod.POST)
                                    .matchesHeaders("foo")
                                    .build());
    }

    private static void testDuplicateRoutes(Route... routes) {
        assertThatThrownBy(() -> Routers.routers(ImmutableList.copyOf(routes), null, null,
                                                 Function.identity(), REJECT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("duplicate route:");
    }

    private static void testNonDuplicateRoutes(Route... routes) {
        Routers.routers(ImmutableList.copyOf(routes), null, null, Function.identity(), REJECT, false);
    }

    @Test
    void exactPathAndParameter() {
        final List<HttpMethod> methods =
                ImmutableList.of(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.POST, HttpMethod.PUT);
        final AtomicInteger i = new AtomicInteger(0);
        final List<Route> routes =
                methods.stream()
                       .map(method -> Route.builder().path(i.incrementAndGet() % 2 == 0 ? "/foo" : "/{id}")
                                           .methods(method).build())
                       .collect(toImmutableList());
        final List<Router<Route>> routers =
                Routers.routers(routes, null, null, Function.identity(), REJECT, false);
        assertThat(routers.size()).isOne();

        methods.forEach(method -> {
            final RoutingContext ctx = mock(RoutingContext.class);
            when(ctx.method()).thenReturn(method);
            when(ctx.path()).thenReturn("/foo");
            when(ctx.query()).thenReturn(null);
            when(ctx.acceptTypes()).thenReturn(ImmutableList.of());
            when(ctx.contentType()).thenReturn(null);

            final Routed<Route> routed = routers.get(0).find(ctx);
            assertThat(routed.route().methods()).contains(method);
        });
    }
}
