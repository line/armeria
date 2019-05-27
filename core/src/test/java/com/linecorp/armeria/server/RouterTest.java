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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.assertj.core.util.Lists;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

public class RouterTest {
    private static final Logger logger = LoggerFactory.getLogger(RouterTest.class);

    private static final BiConsumer<Route, Route> REJECT = (a, b) -> {
        throw new IllegalStateException("duplicate route: " + a + "vs. " + b);
    };

    @Test
    public void testRouters() {
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
        final List<Router<Route>> routers = Routers.routers(routes, Function.identity(), REJECT);
        assertThat(routers.size()).isEqualTo(5);

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

        final RoutingContext routingCtx = mock(RoutingContext.class);
        args.forEach(entry -> {
            logger.debug("Entry: path {} router {}", entry.getKey(), entry.getValue());
            for (int i = 0; i < 5; i++) {
                when(routingCtx.path()).thenReturn(entry.getKey());
                final Routed<Route> result = routers.get(i).find(routingCtx);
                assertThat(result.isPresent()).isEqualTo(i == entry.getValue());
            }
        });
    }

    @Test
    public void duplicateRoutes() {
        // Simple cases
        testDuplicateRoutes(Route.builder().path("exact:/a").build(),
                            Route.builder().path("exact:/a").build());
        testDuplicateRoutes(Route.builder().path("exact:/a").build(),
                            Route.builder().path("/a").build());
        testDuplicateRoutes(Route.builder().path("prefix:/").build(),
                            Route.builder().catchAll().build());
    }

    /**
     * Should detect the duplicates even if the mappings are split into more than one router.
     */
    @Test
    public void duplicateMappingsWithRegex() {
        // Ensure that 3 routers are created first really.
        assertThat(Routers.routers(ImmutableList.of(Route.builder().path("/foo/:bar").build(),
                                                    Route.builder().regex("not-trie-compatible").build(),
                                                    Route.builder().path("/bar/:baz").build()),
                                   Function.identity(), REJECT)).hasSize(3);

        testDuplicateRoutes(Route.builder().path("/foo/:bar").build(),
                            Route.builder().regex("not-trie-compatible").build(),
                            Route.builder().path("/foo/:qux").build());
    }

    @Test
    public void duplicatePathWithHeaders() {
        // Not a duplicate if complexity is different.
        testNonDuplicateRoutes(Route.builder().path("/foo").build(),
                               Route.builder().path("/foo").methods(HttpMethod.GET).build());

        // Duplicate if supported methods overlap.
        testDuplicateRoutes(Route.builder().path("/foo").methods(HttpMethod.GET).build(),
                            Route.builder().path("/foo").methods(HttpMethod.GET, HttpMethod.POST).build());

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

    private static void testDuplicateRoutes(Route... routes) {
        assertThatThrownBy(() -> Routers.routers(ImmutableList.copyOf(routes), Function.identity(), REJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("duplicate route:");
    }

    private static void testNonDuplicateRoutes(Route... routes) {
        Routers.routers(ImmutableList.copyOf(routes), Function.identity(), REJECT);
    }
}
